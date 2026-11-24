"""One pass: ask the hub what's new, upload what isn't on the VPS yet."""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, tzinfo
from typing import Awaitable, Callable

from . import __version__
from .config import Config
from .hub import CameraSearchError, HubError, ModeSwitchError, SearchTruncated
from .mode_request import PATH as MODE_REQUEST, SWITCHABLE, parse_request
from .model import Camera
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
    cameras: int | None = field(default=None, compare=False)  # known cameras (the heartbeat's `cameras`)
    cameras_offline: list[str] | None = field(default=None, compare=False)  # None: no status this cycle
    cameras_search_failing: list[str] = field(default_factory=list, compare=False)  # names, this cycle


class ClipFailed(Exception):
    """This clip could not be processed; count an attempt and move on."""


class CycleAborted(HubError):
    """Failures that are not the clips' fault (ffmpeg missing, every download failing).

    A HubError so the main loop backs off and reconnects to the hub.
    """


MAX_CONSECUTIVE_FAILURES = 2
SEARCH_SLICE = timedelta(hours=6)  # longer spans are searched in slices (reolink-aio stops after 50 pages)
MIN_SLICE = timedelta(minutes=15)  # a truncated slice is halved down to this
BEHIND = timedelta(days=1)  # a camera whose search fails this far behind is logged as an error
MODE_REQUEST_MAX_AGE = timedelta(minutes=5)

_last_bad_request: str | None = None


def _wall_now() -> datetime:
    return datetime.now().astimezone()


async def handle_mode_request(hub, dav, store: StateStore,
                              wall_now: datetime) -> tuple[dict | None, str | None]:
    """Apply mode-request.json at most once, never after it expired. Never raises.

    Returns (result, confirmed mode). The result is the heartbeat's `mode_request`
    ({"id","status","error","at"}) when a request was handled now, None when there was
    nothing (new) to handle. The confirmed mode is the requested mode when the switch's
    read-back showed the hub in it, else None. `wall_now` is this Mac's own clock (aware):
    the request's times come from the VPS, not the hub.
    """
    global _last_bad_request
    try:
        text = await dav.get_text(MODE_REQUEST)
    except Exception as exc:  # a request is never a reason to stop uploading
        log.warning("could not read %s: %s: %s", MODE_REQUEST, type(exc).__name__, exc)
        return None, None
    try:
        request = parse_request(text)
    except Exception as exc:  # parse_request never raises; belt and braces for the cycle
        log.warning("could not parse %s: %s", MODE_REQUEST, type(exc).__name__)
        request = None
    if request is None:
        if text and text.strip() and text != _last_bad_request:
            log.warning("ignoring a malformed %s", MODE_REQUEST)
        _last_bad_request = text
        return None, None
    confirmed = None
    try:
        if store.get_applied_mode_request() == request.id:
            return None, None
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
            status, error, confirmed = await _switch(hub, request.mode, expires)
        result = {"id": request.id, "status": status, "error": error,
                  "at": wall_now.isoformat(timespec="seconds")}
        store.set_applied_mode_request(request.id, result)
    except Exception as exc:
        log.error("mode request %s: %s: %s", request.id, type(exc).__name__, exc)
        confirmed = None
        result = {"id": request.id, "status": "failed", "error": "uploader error",
                  "at": wall_now.isoformat(timespec="seconds")}
    log.info("mode request %s: %s%s", request.id, result["status"],
             f" ({result['error']})" if result["error"] else "")
    return result, confirmed


async def _switch(hub, mode: str, not_after: datetime) -> tuple[str, str | None, str | None]:
    """Switch, then read the mode back: (status, short reason or None, `mode` when read back, else None).

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
        return "applied", None, mode
    if error is not None:
        return "failed", error, None
    if after is None:
        return "applied", "not confirmed", None  # the hub accepted it; the read-back failed
    return "failed", "hub did not switch", None


async def run_cycle(hub, dav, store: StateStore, cfg: Config, now: datetime,
                    remux_fn=remux, tz: tzinfo | None = None, on_uploaded=None,
                    wall_clock: Callable[[], datetime] = _wall_now,
                    on_mode_request: Callable[[dict, str | None], Awaitable[None]] | None = None) -> CycleResult:
    """`on_mode_request(result, mode)` reports a handled mode request at once (the heartbeat),
    before this cycle's clips: a long catch-up must not hold the result back past its expiry.
    `mode` is the switch's read-back when it confirmed the requested mode (the page highlights
    it with the result, even if the hub's next read fails), else the hub's mode read now."""
    # Refuse before touching anything if the 15 GB volume isn't behind the share.
    marker = await dav.get_text(MARKER)
    if marker is None or not marker.startswith(MARKER_TEXT):
        raise VolumeMarkerMissing(
            f"volume marker {MARKER} missing or wrong - refusing to upload (is the VPS volume mounted?)")

    mode_request, confirmed = await handle_mode_request(hub, dav, store, wall_clock())
    if mode_request is not None and on_mode_request is not None:
        try:
            await on_mode_request(mode_request, confirmed if confirmed is not None else await hub.mode())
        except Exception as exc:  # reporting is never a reason to stop uploading
            log.warning("mode request result not reported yet: %s: %s", type(exc).__name__, exc)

    settle = timedelta(seconds=cfg.settle_seconds)
    cameras = await _known_cameras(hub, store)
    search = await _search_cameras(hub, store, cfg, now, cameras)
    hub_info = await hub.info()
    mode = await hub.mode()  # never raises; None when unknown

    result = CycleResult(mode=mode, mode_request=mode_request, cameras=len(cameras),
                         cameras_offline=_offline_names(cameras), cameras_search_failing=search.failing)
    still_todo: dict[int, list[datetime]] = {ch: [] for ch in search.searched}
    # Failures in a row with no success between, by camera: two from different cameras say the hub
    # is the problem (back off and reconnect); two from one camera say it's that camera's (e.g. an
    # offline battery camera), so only its other clips wait for the next cycle.
    in_a_row: list[int] = []
    benched: set[int] = set()
    for clip in sorted(search.clips, key=lambda c: (c.start, c.channel, c.end)):
        status = store.status(clip.key)
        if status != "new":
            result.skipped += 1
            continue
        if clip.end > now - settle or clip.channel in benched:
            result.pending += 1
            still_todo[clip.channel].append(clip.start)
            continue
        try:
            await _upload_one(clip, hub, dav, store, cfg, hub_info, remux_fn, tz, mode)
            result.uploaded += 1
            if on_uploaded is not None:
                on_uploaded(clip)
            in_a_row = []
        except ClipFailed as exc:
            result.failed += 1
            in_a_row.append(clip.channel)
            gave_up = store.record_failure(clip.key, str(exc), cfg.max_attempts,
                                           timedelta(hours=cfg.give_up_hours), now)
            if len(in_a_row) >= MAX_CONSECUTIVE_FAILURES and len(set(in_a_row)) > 1:
                raise CycleAborted(f"{len(in_a_row)} clips in a row failed (last: {exc}) - "
                                   "backing off and reconnecting") from exc
            if gave_up:
                log.error("giving up on %s %s after %d attempts: %s",
                          clip.camera_name, clip.start, cfg.max_attempts, exc)
            else:
                log.warning("clip %s %s failed, will retry: %s", clip.camera_name, clip.start, exc)
                still_todo[clip.channel].append(clip.start)
            if in_a_row[-MAX_CONSECUTIVE_FAILURES:] == [clip.channel] * MAX_CONSECUTIVE_FAILURES:
                benched.add(clip.channel)
                log.warning("%s: %d downloads in a row failed - its other clips wait for the next cycle",
                            clip.camera_name, MAX_CONSECUTIVE_FAILURES)

    # Per camera: a camera whose search failed keeps its watermark and is searched from there next
    # time; one whose search failed part way is searched again from the slice that failed.
    for ch, starts in still_todo.items():
        mark = next_watermark(now, starts, settle)
        if ch in search.held:
            mark = min(mark, search.held[ch])
        store.set_camera_watermark(ch, mark)
    if search.link_error is not None:
        raise search.link_error  # after uploading what was found: then back off and reconnect
    return result


async def _known_cameras(hub, store: StateStore) -> dict[int, Camera]:
    """The remembered cameras, refreshed from the hub's slot list (a failed refresh keeps them)."""
    remembered = store.get_known_cameras()
    known = await hub.refresh_cameras(remembered)
    if {ch: (c.name, c.uid) for ch, c in known.items()} != {ch: (c.name, c.uid) for ch, c in remembered.items()}:
        store.set_known_cameras(known)
    return known


def _offline_names(cameras: dict[int, Camera]) -> list[str] | None:
    """Names of the cameras the hub's latest status said are offline; None without any status."""
    if all(c.online is None for c in cameras.values()):
        return None
    return sorted(c.name for c in cameras.values() if c.online is False)


@dataclass
class _Search:
    clips: list = field(default_factory=list)
    searched: set[int] = field(default_factory=set)  # channels searched (at least the first slice)
    held: dict[int, datetime] = field(default_factory=dict)  # channel -> start of the slice that failed
    failing: list[str] = field(default_factory=list)  # names of the cameras whose search failed
    link_error: HubError | None = None  # the link failed: the remaining cameras were not searched


def _search_order(cameras: dict[int, Camera], marks: dict[int, datetime | None]) -> list[Camera]:
    """Most caught-up first: a camera stuck failing, or on its (long) first search, goes last."""
    def key(cam: Camera):
        mark = marks[cam.channel]
        return (mark is None, -(mark - datetime.min).total_seconds() if mark is not None else 0, cam.channel)
    return sorted(cameras.values(), key=key)


async def _search_span(hub, camera: Camera, start: datetime, end: datetime) -> list:
    """One slice; when reolink-aio cut it short (50 pages), its halves instead."""
    try:
        return await hub.list_clips(camera, start, end)
    except SearchTruncated:
        if end - start <= MIN_SLICE:
            raise
        mid = start + (end - start) / 2
        return [*await _search_span(hub, camera, start, mid), *await _search_span(hub, camera, mid, end)]


async def _search_camera(hub, camera: Camera, start: datetime, end: datetime) -> tuple[list, datetime | None, Exception | None]:
    """(clips, start of the slice that failed or None, its error). Slices of at most SEARCH_SLICE,
    oldest first, stopping at the first failure. A link failure (plain HubError) propagates."""
    found: dict[str, object] = {}
    slice_start = start
    while slice_start < end:
        slice_end = min(slice_start + SEARCH_SLICE, end)
        try:
            for clip in await _search_span(hub, camera, slice_start, slice_end):
                found[clip.key] = clip  # a clip starting on a slice boundary is listed twice
        except CameraSearchError as exc:
            return list(found.values()), slice_start, exc
        slice_start = slice_end
    return list(found.values()), None, None


async def _search_cameras(hub, store: StateStore, cfg: Config, now: datetime,
                          cameras: dict[int, Camera]) -> _Search:
    """Search every known camera, online or not, each from its own watermark (a camera's first
    search reaches back to the backfill limit: 0.3.0 had one global watermark, which ran past
    cameras missing from reolink-aio's login list; clips already on the VPS are skipped).

    One camera failing is logged and skipped (its watermark stays). A link failure stops the
    searching (what was found is still uploaded, then the error is raised). Only when every
    camera's search failed is it the hub's problem at once (the last HubError is raised).
    """
    marks = {ch: store.get_camera_watermark(ch) for ch in cameras}
    search, last_error = _Search(), None
    for camera in _search_order(cameras, marks):
        ch, mark = camera.channel, marks[camera.channel]
        if mark is None:
            log.info("first search of %s (channel %s), back to the backfill limit", camera.name, ch)
        start, end = compute_window(now, mark, cfg.backfill_days)
        try:
            clips, failed_at, error = await _search_camera(hub, camera, start, end)
        except HubError as exc:
            last_error = exc
            search.link_error = exc
            search.failing.append(camera.name)
            log.warning("searching %s (channel %s) failed on the hub link - not searching the other "
                        "cameras this cycle: %s", camera.name, ch, exc)
            break
        search.clips.extend(clips)
        if error is not None:
            last_error = error
            search.failing.append(camera.name)
            behind = mark is not None and now - mark > BEHIND
            log.log(logging.ERROR if behind else logging.WARNING,
                    "could not search %s (channel %s) from %s, will retry next cycle%s: %s", camera.name, ch,
                    failed_at, f" (caught up only to {mark}; clips older than {cfg.backfill_days} days are "
                               "no longer searched)" if behind else "", error)
            if failed_at == start:
                continue  # nothing of it searched: its watermark stays
            search.held[ch] = failed_at
        search.searched.add(ch)
    search.failing.sort()
    if cameras and not search.searched:
        raise last_error
    return search


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
