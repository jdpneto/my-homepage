"""One pass: ask the hub what's new, upload what isn't on the VPS yet."""

from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, tzinfo

from . import __version__
from .config import Config
from .hub import HubError
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


class ClipFailed(Exception):
    """This clip could not be processed; count an attempt and move on."""


class CycleAborted(HubError):
    """Failures that are not the clips' fault (ffmpeg missing, every download failing).

    A HubError so the main loop backs off and reconnects to the hub.
    """


MAX_CONSECUTIVE_FAILURES = 2

async def run_cycle(hub, dav, store: StateStore, cfg: Config, now: datetime,
                    remux_fn=remux, tz: tzinfo | None = None, on_uploaded=None) -> CycleResult:
    # Refuse before touching anything if the 15 GB volume isn't behind the share.
    marker = await dav.get_text(MARKER)
    if marker is None or not marker.startswith(MARKER_TEXT):
        raise VolumeMarkerMissing(
            f"volume marker {MARKER} missing or wrong - refusing to upload (is the VPS volume mounted?)")

    settle = timedelta(seconds=cfg.settle_seconds)
    start, end = compute_window(now, store.get_watermark(), cfg.backfill_days)
    clips = await hub.list_clips(start, end)
    hub_info = await hub.info()

    result = CycleResult()
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
            await _upload_one(clip, hub, dav, store, cfg, hub_info, remux_fn, tz)
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


async def _upload_one(clip, hub, dav, store: StateStore, cfg: Config, hub_info: dict, remux_fn, tz) -> None:
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
        sidecar = build_sidecar(clip, hub_info, __version__, tz)

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
