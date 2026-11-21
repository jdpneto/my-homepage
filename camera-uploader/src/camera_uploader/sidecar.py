"""The .json written next to every clip: what only the hub knows about it."""

from __future__ import annotations

from datetime import datetime, tzinfo

from .model import Clip


def _iso(naive_local: datetime, tz: tzinfo | None) -> str:
    # tz=None: resolve in the system zone per date, so DST offsets are right per clip.
    aware = naive_local.replace(tzinfo=tz) if tz is not None else naive_local.astimezone()
    return aware.isoformat(timespec="seconds")


def build_sidecar(clip: Clip, hub_info: dict, uploader_version: str, tz: tzinfo | None,
                  mode: str | None = None) -> dict:
    return {
        "camera_name": clip.camera_name,
        "camera_id": clip.camera_id,
        "channel": clip.channel,
        "start": _iso(clip.start, tz),
        "end": _iso(clip.end, tz),
        "duration_seconds": round((clip.end - clip.start).total_seconds()),
        "triggers": sorted(clip.triggers),
        "hub_model": hub_info.get("model"),
        "hub_firmware": hub_info.get("firmware"),
        "uploader_version": uploader_version,
        "mode": mode,  # the hub's active mode when the clip was uploaded ("away", "home", ...) or None
    }
