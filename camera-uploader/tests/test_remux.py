import shutil
import struct
import subprocess
from pathlib import Path

import pytest

from camera_uploader.remux import RemuxError, remux

FFMPEG = shutil.which("ffmpeg")
needs_ffmpeg = pytest.mark.skipif(FFMPEG is None, reason="ffmpeg not on PATH")


def top_level_atoms(path: Path) -> list[str]:
    data, atoms, i = path.read_bytes(), [], 0
    while i + 8 <= len(data):
        size, kind = struct.unpack(">I4s", data[i:i + 8])
        if size == 1:
            size = struct.unpack(">Q", data[i + 8:i + 16])[0]
        atoms.append(kind.decode("latin-1"))
        if size < 8:
            break
        i += size
    return atoms


def make_fragmented(path: Path) -> None:
    # Same shape as reolink-aio's download_mp4 output: fragmented MP4.
    subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=15:duration=2",
                    "-c:v", "libx264", "-g", "15", "-movflags", "frag_keyframe+empty_moov", str(path)], check=True)


@needs_ffmpeg
def test_remux_makes_regular_faststart_mp4(tmp_path):
    frag, out = tmp_path / "frag.mp4", tmp_path / "out.mp4"
    make_fragmented(frag)
    assert "moof" in top_level_atoms(frag)  # sanity: input really is fragmented
    remux(frag, out, FFMPEG)
    atoms = top_level_atoms(out)
    assert "moof" not in atoms
    assert atoms.index("moov") < atoms.index("mdat")


@needs_ffmpeg
def test_remux_bad_input_raises(tmp_path):
    junk, out = tmp_path / "junk.mp4", tmp_path / "out.mp4"
    junk.write_bytes(b"\x00not a video" * 10)
    with pytest.raises(RemuxError):
        remux(junk, out, FFMPEG)


def test_remux_missing_ffmpeg_raises(tmp_path):
    src = tmp_path / "in.mp4"
    src.write_bytes(b"x")
    with pytest.raises(RemuxError, match="ffmpeg"):
        remux(src, tmp_path / "out.mp4", str(tmp_path / "no-such-ffmpeg"))
