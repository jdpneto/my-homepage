"""Turn reolink-aio's fragmented MP4 into a regular fast-start MP4 (no re-encode)."""

from __future__ import annotations

import subprocess
from pathlib import Path

TIMEOUT_SECONDS = 120


class RemuxError(Exception):
    """ffmpeg could not remux the clip."""


class RemuxUnavailable(RemuxError):
    """ffmpeg itself is missing or cannot run - not the clip's fault."""


def remux(src: Path, dst: Path, ffmpeg: str) -> None:
    cmd = [ffmpeg, "-nostdin", "-v", "error", "-y", "-i", str(src), "-c", "copy",
           "-movflags", "+faststart", str(dst)]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT_SECONDS)
    except OSError as exc:  # missing, not executable, broken binary
        raise RemuxUnavailable(f"cannot run ffmpeg at {ffmpeg}: {exc}") from None
    except subprocess.TimeoutExpired:
        raise RemuxError(f"ffmpeg timed out after {TIMEOUT_SECONDS}s on {src.name}") from None
    if result.returncode != 0:
        last = (result.stderr.strip().splitlines() or ["no output"])[-1]
        raise RemuxError(f"ffmpeg exit {result.returncode} on {src.name}: {last}")
    if not dst.exists() or dst.stat().st_size == 0:
        raise RemuxError(f"ffmpeg produced no output for {src.name}")
