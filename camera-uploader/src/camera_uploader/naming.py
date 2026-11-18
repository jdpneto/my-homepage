"""Where a clip lands on the WebDAV share: <Camera>/<YYYY-MM-DD>/<HH-MM-SS>[-N]."""

from __future__ import annotations

import re
import unicodedata

from .model import Clip

_WS = re.compile(r"\s+")


def sanitize_camera(name: str) -> str:
    """Folder-safe camera name: no path separators or control chars; accents and spaces kept."""
    text = _WS.sub(" ", name.strip())
    text = "".join("_" if ch in "/\\" or unicodedata.category(ch) == "Cc" else ch for ch in text).strip()
    return "camera" if text in ("", ".", "..") else text


def remote_stem(clip: Clip, suffix: int = 1) -> str:
    stem = f"{sanitize_camera(clip.camera_name)}/{clip.start:%Y-%m-%d}/{clip.start:%H-%M-%S}"
    return stem if suffix <= 1 else f"{stem}-{suffix}"
