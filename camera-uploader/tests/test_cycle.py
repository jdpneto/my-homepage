import json
import shutil
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from camera_uploader.config import Config
from camera_uploader.cycle import CycleResult, run_cycle
from camera_uploader.hub import HubError
from camera_uploader.model import Clip
from camera_uploader.remux import RemuxError
from camera_uploader.state import StateStore
from camera_uploader.webdav import QuotaFullError, TransientError, WebDavError

NOW = datetime(2026, 11, 18, 21, 0, 0)
TZ = ZoneInfo("Europe/Lisbon")


def clip(minutes_ago, length=30, name="Backyard", cid="uid1", ch=1, second=0):
    start = NOW - timedelta(minutes=minutes_ago) + timedelta(seconds=second)
    return Clip(ch, name, cid, start, start + timedelta(seconds=length), frozenset({"motion"}))


class FakeHub:
    def __init__(self, clips, payload=b"video-bytes", fail_download=None):
        self.clips, self.payload, self.fail_download = clips, payload, fail_download or set()
        self.downloads, self.windows = [], []

    async def list_clips(self, start, end):
        self.windows.append((start, end))
        return [c for c in self.clips if start <= c.start <= end]

    async def download(self, c, dest: Path):
        self.downloads.append(c.key)
        if c.key in self.fail_download:
            raise HubError("download broke")
        dest.write_bytes(self.payload)
        return len(self.payload)

    async def info(self):
        return {"model": "Reolink Home Hub Mini", "firmware": "v3.3.0.609_26092101"}


class FakeDav:
    def __init__(self, marker=True, fail=None):
        self.marker, self.calls, self.fail = marker, [], fail or {}
        self.files: dict[str, bytes] = {}

    def _maybe_fail(self, op, path):
        for (fop, suffix), exc in list(self.fail.items()):
            if fop == op and path.endswith(suffix):
                if isinstance(exc, list):  # fail once
                    del self.fail[(fop, suffix)]
                    raise exc[0]
                raise exc

    async def exists(self, path):
        self.calls.append(("PROPFIND", path))
        return self.marker

    async def ensure_dirs(self, stem):
        self.calls.append(("MKCOL", stem))

    async def put_file(self, path, src, content_type):
        self.calls.append(("PUT", path))
        self._maybe_fail("PUT", path)
        self.files[path] = src.read_bytes()

    async def put_bytes(self, path, data, content_type):
        self.calls.append(("PUT", path))
        self._maybe_fail("PUT", path)
        self.files[path] = data


def copy_remux(src, dst, ffmpeg):
    shutil.copyfile(src, dst)


def broken_remux(src, dst, ffmpeg):
    raise RemuxError("corrupt")


@pytest.fixture
def cfg(tmp_path):
    return Config(hub_host="h", hub_user="u", hub_password="p", webdav_url="w", webdav_user="cameras",
                  webdav_password="p", state_path=tmp_path / "state.db", tmp_dir=tmp_path / "tmp",
                  log_path=tmp_path / "log", poll_seconds=30, settle_seconds=20, backfill_days=30, max_attempts=5)


@pytest.fixture
def store(cfg):
    return StateStore(cfg.state_path)


async def run(hub, dav, store, cfg, now=NOW, remux_fn=copy_remux):
    return await run_cycle(hub, dav, store, cfg, now, remux_fn=remux_fn, tz=TZ)


async def test_uploads_new_clip_mp4_then_json(cfg, store):
    c = clip(5)
    dav = FakeDav()
    result = await run(FakeHub([c]), dav, store, cfg)
    puts = [p for op, p in dav.calls if op == "PUT"]
    stem = f"Backyard/{c.start:%Y-%m-%d}/{c.start:%H-%M-%S}"
    assert puts == [f"{stem}.mp4", f"{stem}.json"]
    assert ("MKCOL", stem) in dav.calls
    assert dav.files[f"{stem}.mp4"] == b"video-bytes"
    side = json.loads(dav.files[f"{stem}.json"])
    assert side["camera_id"] == "uid1" and side["triggers"] == ["motion"]
    assert store.status(c.key) == "uploaded"
    assert result == CycleResult(uploaded=1, skipped=0, pending=0, failed=0)


async def test_skips_already_uploaded(cfg, store):
    hub, c = None, clip(5)
    await run(FakeHub([c]), FakeDav(), store, cfg)
    dav, hub = FakeDav(), FakeHub([c])
    result = await run(hub, dav, store, cfg, now=NOW + timedelta(seconds=30))
    assert [op for op, _ in dav.calls if op == "PUT"] == []
    assert hub.downloads == []
    assert result.skipped == 1


async def test_unsettled_clip_is_pending_not_uploaded(cfg, store):
    c = Clip(1, "Backyard", "uid1", NOW - timedelta(seconds=35), NOW - timedelta(seconds=5), frozenset({"motion"}))
    dav, hub = FakeDav(), FakeHub([c])
    result = await run(hub, dav, store, cfg)
    assert result.pending == 1 and hub.downloads == []
    assert [op for op, _ in dav.calls if op == "PUT"] == []
    assert store.get_watermark() <= c.start


async def test_crash_after_mp4_before_json_reuploads_both(cfg, store):
    c = clip(5)
    dav = FakeDav(fail={("PUT", ".json"): [TransientError("network")]})
    with pytest.raises(TransientError):
        await run(FakeHub([c]), dav, store, cfg)
    assert store.status(c.key) == "new"
    dav2 = FakeDav()
    await run(FakeHub([c]), dav2, store, cfg, now=NOW + timedelta(seconds=30))
    assert [p.rsplit(".", 1)[1] for op, p in dav2.calls if op == "PUT"] == ["mp4", "json"]
    assert store.status(c.key) == "uploaded"


async def test_quota_full_stops_cycle(cfg, store):
    a, b = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    hub = FakeHub([a, b])
    dav = FakeDav(fail={("PUT", ".mp4"): QuotaFullError("507")})
    with pytest.raises(QuotaFullError):
        await run(hub, dav, store, cfg)
    assert hub.downloads == [a.key]  # b never touched
    assert store.status(a.key) == "new"


async def test_missing_marker_refuses_before_any_upload(cfg, store):
    hub, dav = FakeHub([clip(5)]), FakeDav(marker=False)
    with pytest.raises(WebDavError, match="volume marker"):
        await run(hub, dav, store, cfg)
    assert [op for op, _ in dav.calls if op != "PROPFIND"] == []
    assert hub.downloads == []


async def test_bad_clip_fails_after_5_attempts_and_releases_watermark(cfg, store):
    bad, good = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    for i in range(5):
        result = await run(FakeHub([bad]), FakeDav(), store, cfg, now=NOW + timedelta(seconds=30 * i),
                           remux_fn=broken_remux)
        assert result.failed == 1
    assert store.status(bad.key) == "failed"
    assert store.get_watermark() > bad.start
    dav = FakeDav()
    result = await run(FakeHub([bad, good]), dav, store, cfg, now=NOW + timedelta(minutes=3))
    assert store.status(good.key) == "uploaded" and result.uploaded == 1


async def test_hub_download_error_counts_as_failure_and_continues(cfg, store):
    a, b = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    hub = FakeHub([a, b], fail_download={a.key})
    result = await run(hub, FakeDav(), store, cfg)
    assert result == CycleResult(uploaded=1, skipped=0, pending=0, failed=1)
    assert store.status(a.key) == "new" and store.status(b.key) == "uploaded"
    assert store.get_watermark() == a.start  # a still to do


async def test_empty_download_is_a_failure(cfg, store):
    result = await run(FakeHub([clip(5)], payload=b""), FakeDav(), store, cfg)
    assert result.failed == 1 and result.uploaded == 0


async def test_same_second_collision_gets_suffix(cfg, store):
    a = clip(5, length=10)
    b = clip(5, length=20)  # same camera, same start second, different end
    dav = FakeDav()
    await run(FakeHub([a, b]), dav, store, cfg)
    stem = f"Backyard/{a.start:%Y-%m-%d}/{a.start:%H-%M-%S}"
    puts = [p for op, p in dav.calls if op == "PUT" and p.endswith(".mp4")]
    assert puts == [f"{stem}.mp4", f"{stem}-2.mp4"]


async def test_temp_files_removed_after_success_and_failure(cfg, store):
    await run(FakeHub([clip(5)]), FakeDav(), store, cfg)
    await run(FakeHub([clip(6)]), FakeDav(), store, cfg, remux_fn=broken_remux)
    assert list(cfg.tmp_dir.iterdir()) == []


async def test_first_run_window_is_backfill_then_follows_watermark(cfg, store):
    hub = FakeHub([clip(5)])
    await run(hub, FakeDav(), store, cfg)
    assert hub.windows[0] == (NOW - timedelta(days=30), NOW)
    later = NOW + timedelta(minutes=1)
    await run(hub, FakeDav(), store, cfg, now=later)
    assert hub.windows[1][0] == NOW - timedelta(seconds=20) - timedelta(minutes=10)
