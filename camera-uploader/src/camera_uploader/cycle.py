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
from .remux import RemuxError, remux
from .sidecar import build_sidecar
from .state import StateStore
from .webdav import WebDavError
from .window import compute_window, next_watermark

log = logging.getLogger(__name__)

MARKER = "volume-marker.txt"


@dataclass
class CycleResult:
    uploaded: int = 0
    skipped: int = 0
    pending: int = 0
    failed: int = 0


class ClipFailed(Exception):
    """This clip could not be processed; count an attempt and move on."""


async def run_cycle(hub, dav, store: StateStore, cfg: Config, now: datetime,
                    remux_fn=remux, tz: tzinfo | None = None) -> CycleResult:
    # Refuse before touching anything if the 15 GB volume isn't behind the share.
    if not await dav.exists(MARKER):
        raise WebDavError(f"volume marker {MARKER} missing - refusing to upload (is the VPS volume mounted?)")

    settle = timedelta(seconds=cfg.settle_seconds)
    start, end = compute_window(now, store.get_watermark(), cfg.backfill_days)
    clips = await hub.list_clips(start, end)
    hub_info = await hub.info()

    result = CycleResult()
    still_todo: list[datetime] = []
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
        except ClipFailed as exc:
            result.failed += 1
            if store.record_failure(clip.key, str(exc), cfg.max_attempts):
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
        except RemuxError as exc:
            raise ClipFailed(f"remux: {exc}") from exc

        suffix = 1
        while store.stem_taken_by_other(remote_stem(clip, suffix), clip.key):
            suffix += 1
        stem = remote_stem(clip, suffix)
        sidecar = build_sidecar(clip, hub_info, __version__, tz)

        # WebDAV errors (auth, quota, network) propagate: they stop the cycle.
        await dav.ensure_dirs(stem)
        await dav.put_file(f"{stem}.mp4", out, "video/mp4")
        await dav.put_bytes(f"{stem}.json", json.dumps(sidecar, ensure_ascii=False, indent=2).encode(),
                            "application/json")
        store.mark_uploaded(clip.key, stem)
        log.info("uploaded %s %s -> %s.mp4 (%.1f MB, %.1fs)", clip.camera_name, clip.start, stem,
                 out.stat().st_size / 1e6, time.monotonic() - started)
    finally:
        raw.unlink(missing_ok=True)
        out.unlink(missing_ok=True)
