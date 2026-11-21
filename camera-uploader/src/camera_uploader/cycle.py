"""One pass: ask the hub what's new, upload what isn't on the VPS yet."""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, tzinfo
from typing import Awaitable, Callable

from . import __version__
from .config import Config
from .hub import HubError, ModeSwitchError
from .mode_request import PATH as MODE_REQUEST, SWITCHABLE, parse_request
from .naming import remote_stem
from .remux import RemuxError, RemuxUnavailable, remux
from .sidecar import build_sidecar
from .state import StateStore
from .webdav import AuthError, QuotaFullError, TransientError, WebDavError
from .window import compute_window, next_watermark

log = logging.getLogger(__name__)

MARKER = "volume-marker.txt"
MARKER_TEXT = "camera clips live on the 15 GB volume"  # written on the VPS by Task 1


class VolumeMarkerMissing(WebDavError):
    """The share isn't backed by the 15 GB volume; uploading would fill the VPS root disk."""


@dataclass
class CycleResult:
    uploaded: int = 0
    skipped: int = 0
    pending: int = 0
    failed: int = 0
    mode: str | None = None
    mode_request: dict | None = None  # the heartbeat's `mode_request` when a request was handled this cycle


class ClipFailed(Exception):
    """This clip could not be processed; count an attempt and move on."""


class CycleAborted(HubError):
    """Failures that are not the clips' fault (ffmpeg missing, every download failing).

    A HubError so the main loop backs off and reconnects to the hub.
    """


MAX_CONSECUTIVE_FAILURES = 2
MODE_REQUEST_MAX_AGE = timedelta(minutes=5)

_last_bad_request: str | None = None


def _wall_now() -> datetime:
    return datetime.now().astimezone()


async def handle_mode_request(hub, dav, store: StateStore, wall_now: datetime) -> dict | None:
    """Apply mode-request.json at most once, never after it expired. Never raises.

    Returns the heartbeat's `mode_request` ({"id","status","error","at"}) when a request
    was handled now, None when there was nothing (new) to handle. `wall_now` is this
    Mac's own clock (aware): the request's times come from the VPS, not the hub.
    """
    global _last_bad_request
    try:
        text = await dav.get_text(MODE_REQUEST)
    except Exception as exc:  # a request is never a reason to stop uploading
        log.warning("could not read %s: %s: %s", MODE_REQUEST, type(exc).__name__, exc)
        return None
    try:
        request = parse_request(text)
    except Exception as exc:  # parse_request never raises; belt and braces for the cycle
        log.warning("could not parse %s: %s", MODE_REQUEST, type(exc).__name__)
        request = None
    if request is None:
        if text and text.strip() and text != _last_bad_request:
            log.warning("ignoring a malformed %s", MODE_REQUEST)
        _last_bad_request = text
        return None
    try:
        if store.get_applied_mode_request() == request.id:
            return None
        log.info("mode request %s: switch to %r", request.id, request.mode)
        expires = min(request.expires_at, request.requested_at + MODE_REQUEST_MAX_AGE)
        status, error = "failed", None
        if wall_now >= expires:
            error = "expired before pickup"
        elif request.mode not in SWITCHABLE:
            error = "unknown mode"
        # Remembered before the hub write: a crash or timeout can never make it happen twice.
        store.set_applied_mode_request(request.id)
        if error is None:
            status, error = await _switch(hub, request.mode, expires)
        result = {"id": request.id, "status": status, "error": error,
                  "at": wall_now.isoformat(timespec="seconds")}
        store.set_applied_mode_request(request.id, result)
    except Exception as exc:
        log.error("mode request %s: %s: %s", request.id, type(exc).__name__, exc)
        result = {"id": request.id, "status": "failed", "error": "uploader error",
                  "at": wall_now.isoformat(timespec="seconds")}
    log.info("mode request %s: %s%s", request.id, result["status"],
             f" ({result['error']})" if result["error"] else "")
    return result


async def _switch(hub, mode: str, not_after: datetime) -> tuple[str, str | None]:
    """Switch, then read the mode back: (status, short reason or None).

    The read-back decides even when the switch raised: cmd 602 may have gone through
    before the follow-up read or the wait failed, and then the hub *is* in `mode`.
    """
    error = None
    try:
        await hub.switch_mode(mode, not_after=not_after)  # the expiry is re-checked right before the write
    except ModeSwitchError as exc:
        error = str(exc) or "hub refused"
    except TimeoutError:
        error = "hub timed out"
    except Exception as exc:
        log.warning("mode switch to %s failed: %s: %s", mode, type(exc).__name__, exc)
        error = "hub refused"
    after = await hub.mode()  # never raises; None when unknown
    if after == mode:
        if error is not None:
            log.info("the hub reported %r switching to %s, but it is in %s now", error, mode, mode)
        return "applied", None
    if error is not None:
        return "failed", error
    if after is None:
        return "applied", "not confirmed"  # the hub accepted it; the read-back failed
    return "failed", "hub did not switch"


async def run_cycle(hub, dav, store: StateStore, cfg: Config, now: datetime,
                    remux_fn=remux, tz: tzinfo | None = None, on_uploaded=None,
                    wall_clock: Callable[[], datetime] = _wall_now,
                    on_mode_request: Callable[[dict, str | None], Awaitable[None]] | None = None) -> CycleResult:
    """`on_mode_request(result, mode)` reports a handled mode request at once (the heartbeat),
    before this cycle's clips: a long catch-up must not hold the result back past its expiry."""
    # Refuse before touching anything if the 15 GB volume isn't behind the share.
    marker = await dav.get_text(MARKER)
    if marker is None or not marker.startswith(MARKER_TEXT):
        raise VolumeMarkerMissing(
            f"volume marker {MARKER} missing or wrong - refusing to upload (is the VPS volume mounted?)")

    mode_request = await handle_mode_request(hub, dav, store, wall_clock())
    if mode_request is not None and on_mode_request is not None:
        try:
            await on_mode_request(mode_request, await hub.mode())
        except Exception as exc:  # reporting is never a reason to stop uploading
            log.warning("mode request result not reported yet: %s: %s", type(exc).__name__, exc)

    settle = timedelta(seconds=cfg.settle_seconds)
    start, end = compute_window(now, store.get_watermark(), cfg.backfill_days)
    clips = await hub.list_clips(start, end)
    hub_info = await hub.info()
    mode = await hub.mode()  # never raises; None when unknown

    result = CycleResult(mode=mode, mode_request=mode_request)
    still_todo: list[datetime] = []
    consecutive_failures = 0
    for clip in sorted(clips, key=lambda c: (c.start, c.channel, c.end)):
        status = store.status(clip.key)
        if status != "new":
            result.skipped += 1
            continue
        if clip.end > now - settle:
            result.pending += 1
            still_todo.append(clip.start)
            continue
        try:
            await _upload_one(clip, hub, dav, store, cfg, hub_info, remux_fn, tz, mode)
            result.uploaded += 1
            if on_uploaded is not None:
                on_uploaded(clip)
            consecutive_failures = 0
        except ClipFailed as exc:
            result.failed += 1
            consecutive_failures += 1
            gave_up = store.record_failure(clip.key, str(exc), cfg.max_attempts,
                                           timedelta(hours=cfg.give_up_hours), now)
            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                raise CycleAborted(f"{consecutive_failures} clips in a row failed (last: {exc}) - "
                                   "backing off and reconnecting") from exc
            if gave_up:
                log.error("giving up on %s %s after %d attempts: %s",
                          clip.camera_name, clip.start, cfg.max_attempts, exc)
            else:
                log.warning("clip %s %s failed, will retry: %s", clip.camera_name, clip.start, exc)
                still_todo.append(clip.start)

    store.set_watermark(next_watermark(now, still_todo, settle))
    return result


async def _upload_one(clip, hub, dav, store: StateStore, cfg: Config, hub_info: dict, remux_fn, tz,
                      mode: str | None = None) -> None:
    cfg.tmp_dir.mkdir(parents=True, exist_ok=True)
    token = uuid.uuid4().hex
    raw = cfg.tmp_dir / f"{token}.raw.mp4"
    out = cfg.tmp_dir / f"{token}.mp4"
    started = time.monotonic()
    try:
        try:
            size = await hub.download(clip, raw)
        except HubError as exc:
            raise ClipFailed(f"download: {exc}") from exc
        if size == 0:
            raise ClipFailed("download returned 0 bytes")
        try:
            remux_fn(raw, out, cfg.ffmpeg)
        except RemuxUnavailable as exc:
            raise CycleAborted(f"remux: {exc}") from exc  # not the clip's fault: don't charge it
        except RemuxError as exc:
            raise ClipFailed(f"remux: {exc}") from exc

        suffix = 1
        while store.stem_taken_by_other(remote_stem(clip, suffix), clip.key):
            suffix += 1
        stem = remote_stem(clip, suffix)
        sidecar = build_sidecar(clip, hub_info, __version__, tz, mode)

        # Auth, quota and network errors propagate: they stop the cycle. Any other
        # WebDAV error (e.g. a 4xx on this clip's path) is this clip's problem.
        try:
            await dav.ensure_dirs(stem)
            await dav.put_file(f"{stem}.mp4", out, "video/mp4")
            await dav.put_bytes(f"{stem}.json", json.dumps(sidecar, ensure_ascii=False, indent=2).encode(),
                                "application/json")
        except (AuthError, QuotaFullError, TransientError):
            raise
        except WebDavError as exc:
            raise ClipFailed(f"upload: {exc}") from exc
        store.mark_uploaded(clip.key, stem)
        log.info("uploaded %s %s -> %s.mp4 (%.1f MB, %.1fs)", clip.camera_name, clip.start, stem,
                 out.stat().st_size / 1e6, time.monotonic() - started)
    finally:
        raw.unlink(missing_ok=True)
        out.unlink(missing_ok=True)
