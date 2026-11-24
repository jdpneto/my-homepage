import json
import json
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from camera_uploader.config import Config
from dataclasses import replace

from camera_uploader.cycle import CycleAborted, CycleResult, run_cycle
from camera_uploader.hub import HubError
from camera_uploader.model import Camera, Clip
from camera_uploader.remux import RemuxError, RemuxUnavailable
from camera_uploader.state import StateStore
from camera_uploader.webdav import QuotaFullError, TransientError, WebDavError

NOW = datetime(2026, 11, 18, 21, 0, 0)
TZ = ZoneInfo("Europe/Lisbon")


def clip(minutes_ago, length=30, name="Backyard", cid="uid1", ch=1, second=0):
    start = NOW - timedelta(minutes=minutes_ago) + timedelta(seconds=second)
    return Clip(ch, name, cid, start, start + timedelta(seconds=length), frozenset({"motion"}))


class FakeHub:
    def __init__(self, clips, payload=b"video-bytes", fail_download=None, mode=None,
                 listed_modes=("disarm", "home", "away"), switch_error=None, cameras=None, fail_search=None):
        self.clips, self.payload, self.fail_download = clips, payload, fail_download or set()
        self.downloads, self.windows = [], []
        # What GetChannelstatus reports: by default Backyard (ch 1) plus every camera that has a clip.
        reported = {1: Camera(1, "Backyard", "uid1", online=True)}
        for c in clips:
            reported.setdefault(c.channel, Camera(c.channel, c.camera_name, c.camera_id, online=True))
        self.reported = reported if cameras is None else {c.channel: c for c in cameras}
        self.fail_search = fail_search or set()  # a per-camera failure (the hub answered)
        self.transport_fail: set[int] = set()  # the link failed while searching this camera
        self.fail_at = None  # a time: the search slice containing it fails
        self.truncate_over = None  # a search longer than this hits reolink-aio's 50-page cap
        self.searched: list[int] = []
        self.camera_windows: dict[int, tuple] = {}
        self._mode = mode
        self.listed_modes, self.switch_error = listed_modes, switch_error
        self.switch_calls: list[str] = []  # asked to switch
        self.scene_writes: list[str] = []  # actually switched (the hub write)
        self.not_after: list = []  # the deadline passed with each switch

    async def mode(self):
        return self._mode

    async def switch_mode(self, name, not_after=None):
        from camera_uploader.hub import ModeSwitchError

        self.switch_calls.append(name)
        self.not_after.append(not_after)
        if self.switch_error is not None:
            raise self.switch_error
        if name not in self.listed_modes:
            raise ModeSwitchError("unknown mode")
        self.scene_writes.append(name)
        self._mode = name

    async def refresh_cameras(self, known):
        return {**known, **self.reported}

    async def list_clips(self, camera, start, end):
        from camera_uploader.hub import CameraSearchError, SearchTruncated

        self.windows.append((start, end))
        if not self.searched or self.searched[-1] != camera.channel:
            self.searched.append(camera.channel)
        first = self.camera_windows.get(camera.channel, (start, end))[0]
        self.camera_windows[camera.channel] = (first, end)  # the whole span searched (slices in order)
        if camera.channel in self.fail_search:
            raise CameraSearchError(f"search channel {camera.channel}: InvalidParameterError: cannot get UID")
        if camera.channel in self.transport_fail:
            raise HubError(f"search channel {camera.channel}: ReolinkTimeoutError: no answer")
        if self.fail_at is not None and start <= self.fail_at <= end:
            raise CameraSearchError(f"search channel {camera.channel}: ApiError: busy")
        if self.truncate_over is not None and end - start > self.truncate_over:
            raise SearchTruncated(f"search channel {camera.channel}: more than 50 pages")
        return [c for c in self.clips if c.channel == camera.channel and start <= c.start <= end]

    async def download(self, c, dest: Path):
        self.downloads.append(c.key)
        if c.key in self.fail_download:
            raise HubError("download broke")
        dest.write_bytes(self.payload)
        return len(self.payload)

    async def info(self):
        return {"model": "Reolink Home Hub Mini", "firmware": "v3.3.0.609_26092101"}


class FakeDav:
    def __init__(self, marker=True, fail=None, marker_text=None, texts=None):
        self.marker, self.calls, self.fail = marker, [], fail or {}
        self.texts = texts or {}  # path -> text (None = 404); other paths answer with the marker text
        self.marker_text = marker_text if marker_text is not None else (
            "camera clips live on the 15 GB volume - do not delete\n" if marker else None)
        self.files: dict[str, bytes] = {}

    def _maybe_fail(self, op, path):
        for (fop, suffix), exc in list(self.fail.items()):
            if fop == op and path.endswith(suffix):
                if isinstance(exc, list):  # fail once
                    del self.fail[(fop, suffix)]
                    raise exc[0]
                raise exc

    async def get_text(self, path):
        self.calls.append(("GET", path))
        if path in self.texts:
            return self.texts[path]
        return self.marker_text

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


WALL = datetime(2026, 11, 21, 10, 1, 0, tzinfo=timezone.utc)  # the uploader's own clock (aware)


async def run(hub, dav, store, cfg, now=NOW, remux_fn=copy_remux, wall=WALL):
    return await run_cycle(hub, dav, store, cfg, now, remux_fn=remux_fn, tz=TZ, wall_clock=lambda: wall)


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
    assert store.get_camera_watermark(1) <= c.start


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
    assert [op for op, _ in dav.calls if op != "GET"] == []
    assert hub.downloads == []


async def test_marker_with_wrong_content_refuses(cfg, store):
    # e.g. a server that answers for any path, or an empty dir Docker created on the root disk
    hub, dav = FakeHub([clip(5)]), FakeDav(marker_text="")
    with pytest.raises(WebDavError, match="volume marker"):
        await run(hub, dav, store, cfg)
    assert hub.downloads == []


async def test_bad_clip_fails_after_5_attempts_and_releases_watermark(cfg, store):
    cfg = replace(cfg, give_up_hours=0)
    bad, good = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    for i in range(5):
        result = await run(FakeHub([bad]), FakeDav(), store, cfg, now=NOW + timedelta(seconds=30 * i),
                           remux_fn=broken_remux)
        assert result.failed == 1
    assert store.status(bad.key) == "failed"
    assert store.get_camera_watermark(0) > bad.start
    dav = FakeDav()
    result = await run(FakeHub([bad, good]), dav, store, cfg, now=NOW + timedelta(minutes=3))
    assert store.status(good.key) == "uploaded" and result.uploaded == 1


async def test_hub_download_error_counts_as_failure_and_continues(cfg, store):
    a, b = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    hub = FakeHub([a, b], fail_download={a.key})
    result = await run(hub, FakeDav(), store, cfg)
    assert result == CycleResult(uploaded=1, skipped=0, pending=0, failed=1)
    assert store.status(a.key) == "new" and store.status(b.key) == "uploaded"
    assert store.get_camera_watermark(0) == a.start  # a still to do


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
    assert hub.windows[0][0] == NOW - timedelta(days=30) and hub.windows[-1][1] == NOW  # in slices
    later, before = NOW + timedelta(minutes=1), len(hub.windows)
    await run(hub, FakeDav(), store, cfg, now=later)
    assert hub.windows[before:] == [(NOW - timedelta(seconds=20) - timedelta(minutes=10), later)]



# --- final-review fixes ---------------------------------------------------------

def missing_ffmpeg(src, dst, ffmpeg):
    raise RemuxUnavailable("ffmpeg not found")


async def test_two_consecutive_failures_abort_cycle(cfg, store):
    a, b, c = clip(12, ch=0, cid="u0", name="Garage"), clip(10), clip(5, ch=0, cid="u0", name="Garage")
    hub = FakeHub([a, b, c], fail_download={a.key, b.key})
    with pytest.raises(CycleAborted):
        await run(hub, FakeDav(), store, cfg)
    assert hub.downloads == [a.key, b.key]  # c never tried this cycle
    assert store.status(c.key) == "new"


async def test_missing_ffmpeg_aborts_without_charging_clips(cfg, store):
    cfg = replace(cfg, give_up_hours=0)
    c = clip(5)
    for i in range(7):
        with pytest.raises(CycleAborted):
            await run(FakeHub([c]), FakeDav(), store, cfg, now=NOW + timedelta(seconds=30 * i),
                      remux_fn=missing_ffmpeg)
    assert store.status(c.key) == "new"


async def test_clip_not_given_up_within_give_up_hours(cfg, store):
    bad = clip(10)
    for i in range(8):
        await run(FakeHub([bad]), FakeDav(), store, cfg, now=NOW + timedelta(seconds=30 * i), remux_fn=broken_remux)
    assert store.status(bad.key) == "new"  # cfg.give_up_hours defaults to 6


async def test_webdav_4xx_on_one_clip_does_not_block_queue(cfg, store):
    a, b = clip(10, name="Garage", cid="uid0", ch=0), clip(5)
    dav = FakeDav(fail={("PUT", f"{a.start:%H-%M-%S}.mp4"): WebDavError("HTTP 400")})
    result = await run(FakeHub([a, b]), dav, store, cfg)
    assert store.status(a.key) == "new" and store.status(b.key) == "uploaded"
    assert result.failed == 1 and result.uploaded == 1


async def test_on_uploaded_called_per_clip(cfg, store):
    seen = []
    await run_cycle(FakeHub([clip(10, name="Garage", cid="u0", ch=0), clip(5)]), FakeDav(), store, cfg, NOW,
                    remux_fn=copy_remux, tz=TZ, on_uploaded=seen.append)
    assert len(seen) == 2


async def test_clips_stamped_with_mode(cfg, store):
    c = clip(5)
    dav = FakeDav()
    result = await run(FakeHub([c], mode="away"), dav, store, cfg)
    stem = f"Backyard/{c.start:%Y-%m-%d}/{c.start:%H-%M-%S}"
    assert json.loads(dav.files[f"{stem}.json"])["mode"] == "away"
    assert result.mode == "away"


async def test_mode_failure_does_not_fail_the_cycle(cfg, store):
    c = clip(5)
    dav = FakeDav()
    result = await run(FakeHub([c], mode=None), dav, store, cfg)
    stem = f"Backyard/{c.start:%Y-%m-%d}/{c.start:%H-%M-%S}"
    assert json.loads(dav.files[f"{stem}.json"])["mode"] is None
    assert result.uploaded == 1 and result.mode is None


# --- remote mode switch ---------------------------------------------------------

def mode_request(mode="away", rid="req-1", requested=datetime(2026, 11, 21, 10, 0, 0, tzinfo=timezone.utc)):
    return json.dumps({"id": rid, "mode": mode, "requested_at": requested.isoformat(),
                       "expires_at": (requested + timedelta(minutes=5)).isoformat()})


def request_dav(text):
    return FakeDav(texts={"mode-request.json": text})


async def test_request_applied_once(cfg, store):
    hub = FakeHub([], mode="home")
    first = await run(hub, request_dav(mode_request("away")), store, cfg)
    assert hub.scene_writes == ["away"]
    assert first.mode_request == {"id": "req-1", "status": "applied", "error": None,
                                  "at": "2026-11-21T10:01:00+00:00"}
    assert first.mode == "away"
    # Seen again on the next cycle (and after a restart: the id is in the state DB).
    hub2 = FakeHub([], mode="home")  # e.g. switched back in the Reolink app meanwhile
    second = await run(hub2, request_dav(mode_request("away")), StateStore(cfg.state_path), cfg,
                       wall=WALL + timedelta(seconds=30))
    assert hub2.switch_calls == [] and hub2.scene_writes == []
    assert second.mode_request is None and second.mode == "home"
    # A newer request (new id) is applied.
    third = await run(hub2, request_dav(mode_request("disarm", rid="req-2")), store, cfg,
                      wall=WALL + timedelta(seconds=60))
    assert hub2.scene_writes == ["disarm"] and third.mode_request["status"] == "applied"


async def test_expired_request_is_not_applied(cfg, store):
    # The Mac mini was offline for an hour; the request it finds is long stale.
    hub = FakeHub([clip(5)], mode="home")
    result = await run(hub, request_dav(mode_request("away")), store, cfg, wall=WALL + timedelta(hours=1))
    assert hub.switch_calls == [] and hub.scene_writes == []
    assert result.mode_request["status"] == "failed"
    assert result.mode_request["error"] == "expired before pickup"
    assert result.uploaded == 1
    # Exactly at expires_at counts as expired too; and it is reported once, not every cycle.
    hub2 = FakeHub([], mode="home")
    late = await run(hub2, request_dav(mode_request("away", rid="req-2")), store, cfg,
                     wall=datetime(2026, 11, 21, 10, 5, 0, tzinfo=timezone.utc))
    assert hub2.switch_calls == [] and late.mode_request["error"] == "expired before pickup"
    again = await run(hub2, request_dav(mode_request("away", rid="req-2")), store, cfg,
                      wall=WALL + timedelta(hours=2))
    assert again.mode_request is None


async def test_expiry_capped_at_five_minutes_after_request(cfg, store):
    hub = FakeHub([], mode="home")
    text = json.dumps({"id": "req-9", "mode": "away", "requested_at": "2026-11-21T10:00:00+00:00",
                       "expires_at": "2026-11-22T10:00:00+00:00"})  # a bogus far-future expiry
    result = await run(hub, request_dav(text), store, cfg, wall=WALL + timedelta(minutes=10))
    assert hub.scene_writes == [] and result.mode_request["error"] == "expired before pickup"


async def test_unknown_mode_fails_without_hub_write(cfg, store):
    for i, mode in enumerate(("off", "party", "disarm")):
        hub = FakeHub([clip(5 + i)], mode="home", listed_modes=("home", "away"))  # disarm not listed
        result = await run(hub, request_dav(mode_request(mode, rid=f"req-{mode}")), store, cfg)
        assert hub.scene_writes == [], mode
        assert result.mode_request["status"] == "failed", mode
        assert result.mode_request["error"] == "unknown mode", mode
        assert result.uploaded == 1, mode
        if mode != "disarm":  # off and unknown names never even reach the hub object
            assert hub.switch_calls == [], mode
    assert store.get_applied_mode_request() == "req-disarm"


async def test_switch_failure_does_not_fail_the_cycle(cfg, store):
    from camera_uploader.hub import ModeSwitchError

    for i, error in enumerate((ModeSwitchError("hub refused"), TimeoutError(), RuntimeError("bug"))):
        c = clip(5 + i)
        hub = FakeHub([c], mode="home", switch_error=error)
        dav = request_dav(mode_request("away", rid=f"req-{i}"))
        result = await run(hub, dav, store, cfg)
        assert result.mode_request["status"] == "failed"
        assert result.mode_request["error"]  # a short reason
        assert result.uploaded == 1 and store.status(c.key) == "uploaded"
    # A broken request read (network) is no reason to stop uploading either.
    c = clip(9)
    dav = FakeDav(fail={})
    async def broken_get(path, _orig=dav.get_text):
        if path == "mode-request.json":
            raise TransientError("502")
        return await _orig(path)
    dav.get_text = broken_get
    result = await run(FakeHub([c]), dav, store, cfg)
    assert result.uploaded == 1 and result.mode_request is None


async def test_switch_not_confirmed_by_hub_is_failed(cfg, store):
    class StuckHub(FakeHub):
        async def switch_mode(self, name, not_after=None):
            self.switch_calls.append(name)  # the hub said OK but stays in home

    hub = StuckHub([], mode="home")
    result = await run(hub, request_dav(mode_request("away")), store, cfg)
    assert result.mode_request["status"] == "failed"
    assert result.mode_request["error"] == "hub did not switch"


async def test_no_request_file_no_switch(cfg, store):
    for text in (None, "", "garbage"):
        hub = FakeHub([clip(5)], mode="home")
        dav = request_dav(text)
        result = await run(hub, dav, store, cfg)
        assert hub.switch_calls == [] and result.mode_request is None
    # The request is read after the marker check and before the hub is asked for clips.
    dav = request_dav(None)
    await run(FakeHub([]), dav, store, cfg)
    gets = [p for op, p in dav.calls if op == "GET"]
    assert gets == ["volume-marker.txt", "mode-request.json"]
    # A missing marker refuses before the request is even read.
    dav = FakeDav(marker=False, texts={"mode-request.json": mode_request("away")})
    hub = FakeHub([])
    with pytest.raises(WebDavError, match="volume marker"):
        await run(hub, dav, store, cfg)
    assert hub.switch_calls == [] and [p for op, p in dav.calls] == ["volume-marker.txt"]


async def test_request_id_remembered_before_the_hub_write(cfg, store):
    # A crash (or power cut) during the switch must not let the next cycle switch again.
    seen = []

    class CheckingHub(FakeHub):
        async def switch_mode(self, name, not_after=None):
            seen.append(store.get_applied_mode_request())
            raise KeyboardInterrupt  # the process dies mid-switch

    with pytest.raises(KeyboardInterrupt):
        await run(CheckingHub([], mode="home"), request_dav(mode_request("away")), store, cfg)
    assert seen == ["req-1"]
    hub = FakeHub([], mode="home")
    result = await run(hub, request_dav(mode_request("away")), StateStore(cfg.state_path), cfg)
    assert hub.switch_calls == [] and result.mode_request is None


async def test_hostile_request_does_not_break_the_cycle(cfg, store):
    for i, text in enumerate(("[" * 200_000, "x" * 5_000_000)):
        c = clip(5 + i)
        hub = FakeHub([c], mode="home")
        result = await run(hub, request_dav(text), store, cfg)
        assert hub.switch_calls == [] and result.mode_request is None
        assert result.uploaded == 1 and store.status(c.key) == "uploaded"


async def test_switch_error_but_hub_switched_is_applied(cfg, store):
    # cmd 602 went through, but the follow-up read (or the wait) failed: the hub is in away.
    from camera_uploader.hub import ModeSwitchError

    class SwitchedThenFailedHub(FakeHub):
        async def switch_mode(self, name, not_after=None):
            self.switch_calls.append(name)
            self.scene_writes.append(name)
            self._mode = name
            raise self.switch_error

    for i, error in enumerate((ModeSwitchError("hub timed out"), ModeSwitchError("hub refused"),
                               TimeoutError(), RuntimeError("bug"))):
        hub = SwitchedThenFailedHub([], mode="home", switch_error=error)
        result = await run(hub, request_dav(mode_request("away", rid=f"req-{i}")), store, cfg)
        assert result.mode_request["status"] == "applied", error
        assert result.mode_request["error"] is None, error
        assert result.mode == "away"
    # Failed and the hub did not move: still failed, with the reason.
    hub = FakeHub([], mode="home", switch_error=ModeSwitchError("hub timed out"))
    result = await run(hub, request_dav(mode_request("away", rid="req-x")), store, cfg)
    assert result.mode_request["status"] == "failed" and result.mode_request["error"] == "hub timed out"


async def test_switch_not_read_back_is_applied_but_not_confirmed(cfg, store):
    class BlindHub(FakeHub):
        async def mode(self):
            return None  # the read-back failed

    hub = BlindHub([], mode="home")
    result = await run(hub, request_dav(mode_request("away")), store, cfg)
    assert hub.scene_writes == ["away"]
    assert result.mode_request["status"] == "applied"
    assert result.mode_request["error"] == "not confirmed"


async def test_mode_request_reported_at_once(cfg, store):
    # The result goes out before the cycle's clips: a long catch-up must not leave the page
    # showing "pending" until it flips to "expired" while the hub is already in away.
    events = []

    class TrackingHub(FakeHub):
        async def list_clips(self, camera, start, end):
            events.append("list_clips")
            return await super().list_clips(camera, start, end)

    async def report(result, mode):
        events.append(("report", result["status"], mode))

    hub = TrackingHub([clip(5)], mode="home")
    result = await run_cycle(hub, request_dav(mode_request("away")), store, cfg, NOW, remux_fn=copy_remux,
                             tz=TZ, wall_clock=lambda: WALL, on_mode_request=report)
    assert events[0] == ("report", "applied", "away") and set(events[1:]) == {"list_clips"}
    assert result.mode_request["status"] == "applied" and result.uploaded == 1
    # The switch itself is told the request's deadline (re-checked right before the hub write).
    assert hub.not_after == [datetime(2026, 11, 21, 10, 5, 0, tzinfo=timezone.utc)]

    # Nothing new: no report. A broken report never breaks the cycle.
    events.clear()
    await run_cycle(TrackingHub([]), request_dav(mode_request("away")), store, cfg, NOW,
                    remux_fn=copy_remux, tz=TZ, wall_clock=lambda: WALL, on_mode_request=report)
    assert set(events) == {"list_clips"}

    async def broken(result, mode):
        raise RuntimeError("heartbeat bug")

    c = clip(7)
    result = await run_cycle(FakeHub([c], mode="home"), request_dav(mode_request("home", rid="req-2")), store,
                             cfg, NOW, remux_fn=copy_remux, tz=TZ, wall_clock=lambda: WALL, on_mode_request=broken)
    assert result.mode_request["status"] == "applied" and result.uploaded == 1


async def test_switch_updates_heartbeat_mode_with_the_result(cfg, store):
    # The switch's own read-back confirmed the hub is in away: that mode goes out with the result,
    # even when the hub's next mode read fails (it is busy right after a scene change).
    reports = []

    class BusyAfterSwitchHub(FakeHub):
        reads_after_switch = 0

        async def mode(self):
            if self.scene_writes:
                self.reads_after_switch += 1
                if self.reads_after_switch > 1:
                    return None  # only the switch's read-back got an answer
            return self._mode

    async def report(result, mode):
        reports.append((result["status"], result["error"], mode))

    hub = BusyAfterSwitchHub([], mode="home")
    await run_cycle(hub, request_dav(mode_request("away")), store, cfg, NOW, remux_fn=copy_remux,
                    tz=TZ, wall_clock=lambda: WALL, on_mode_request=report)
    assert reports == [("applied", None, "away")]

    # Not confirmed (the read-back failed) or failed: the hub's mode is read for the report as before.
    reports.clear()

    class BlindHub(FakeHub):
        async def mode(self):
            return None

    await run_cycle(BlindHub([], mode="home"), request_dav(mode_request("away", rid="req-2")), store, cfg,
                    NOW, remux_fn=copy_remux, tz=TZ, wall_clock=lambda: WALL, on_mode_request=report)
    assert reports == [("applied", "not confirmed", None)]
    reports.clear()

    class StuckHub(FakeHub):
        async def switch_mode(self, name, not_after=None):
            self.switch_calls.append(name)  # the hub said OK but stays in home

    await run_cycle(StuckHub([], mode="home"), request_dav(mode_request("away", rid="req-3")), store, cfg,
                    NOW, remux_fn=copy_remux, tz=TZ, wall_clock=lambda: WALL, on_mode_request=report)
    assert reports == [("failed", "hub did not switch", "home")]


# --- every known camera, online or not (22 Nov 2026 incident) ----------------------

GARAGE = Camera(0, "Garage", "uid0")
BACKYARD = Camera(1, "Backyard", "uid1")
LIVING = Camera(2, "Living Room", "uid2")
FRONT = Camera(3, "Front door", "uid3")
FOUR = {c.channel: c for c in (GARAGE, BACKYARD, LIVING, FRONT)}


def cam_clip(cam, minutes_ago, length=30):
    return clip(minutes_ago, length=length, name=cam.name, cid=cam.uid, ch=cam.channel)


async def test_every_known_camera_searched_even_if_offline_at_login(cfg, store):
    store.set_known_cameras(FOUR)
    clips = [cam_clip(c, 10 + c.channel) for c in FOUR.values()]
    # The hub now reports only the Garage; the others are asleep/offline (and Backyard reports no uid).
    hub = FakeHub(clips, cameras=[Camera(0, "Garage", "uid0", online=True)])
    result = await run(hub, FakeDav(), store, cfg)
    assert sorted(hub.searched) == [0, 1, 2, 3]
    assert result.uploaded == 4 and all(store.status(c.key) == "uploaded" for c in clips)
    assert result.cameras == 4
    assert store.get_known_cameras() == FOUR  # still remembered


async def test_offline_cameras_in_cycle_result(cfg, store):
    store.set_known_cameras(FOUR)
    hub = FakeHub([], cameras=[Camera(0, "Garage", "uid0", online=True), Camera(1, "Backyard", "uid1", online=False),
                               Camera(2, "Living Room", "uid2", online=False)])
    result = await run(hub, FakeDav(), store, cfg)
    assert result.cameras == 4 and result.cameras_offline == ["Backyard", "Living Room"]  # Front door: no status

    class RefreshFailedHub(FakeHub):
        async def refresh_cameras(self, known):
            return known

    result = await run(RefreshFailedHub([]), FakeDav(), store, cfg)
    assert result.cameras == 4 and result.cameras_offline is None
    # A camera never seen with a uid is never searched (no slot to search).
    hub = FakeHub([], cameras=[])
    result = await run(hub, FakeDav(), StateStore(cfg.tmp_dir.parent / "empty.db"), cfg)
    assert hub.searched == [] and result.cameras == 0


async def test_per_camera_watermarks_advance_independently(cfg, store):
    settle = timedelta(seconds=cfg.settle_seconds)
    store.set_known_cameras({0: GARAGE, 1: BACKYARD})
    unsettled = Clip(0, "Garage", "uid0", NOW - timedelta(seconds=35), NOW - timedelta(seconds=5),
                     frozenset({"motion"}))
    done = cam_clip(BACKYARD, 5)
    hub = FakeHub([unsettled, done], cameras=[])
    await run(hub, FakeDav(), store, cfg)
    assert store.get_camera_watermark(0) == unsettled.start  # held back by its pending clip
    assert store.get_camera_watermark(1) == NOW - settle  # nothing pending: caught up
    later = NOW + timedelta(minutes=1)
    hub2 = FakeHub([unsettled, done], cameras=[])
    await run(hub2, FakeDav(), store, cfg, now=later)
    overlap = timedelta(minutes=10)
    assert hub2.camera_windows == {0: (unsettled.start - overlap, later), 1: (NOW - settle - overlap, later)}
    assert store.status(unsettled.key) == "uploaded"
    assert store.get_camera_watermark(0) == later - settle


async def test_one_camera_search_failure_does_not_block_others(cfg, store, caplog):
    store.set_known_cameras(FOUR)
    store.set_camera_watermark(2, NOW - timedelta(hours=3))
    clips = [cam_clip(c, 10 + c.channel) for c in FOUR.values()]
    hub = FakeHub(clips, cameras=[], fail_search={2})
    result = await run(hub, FakeDav(), store, cfg)
    assert sorted(hub.searched) == [0, 1, 2, 3]
    assert result.uploaded == 3 and store.status(clips[2].key) == "new"
    assert store.get_camera_watermark(2) == NOW - timedelta(hours=3)  # kept: searched again next time
    assert store.get_camera_watermark(0) == NOW - timedelta(seconds=cfg.settle_seconds)
    assert "Living Room" in caplog.text and "channel 2" in caplog.text
    hub2 = FakeHub(clips, cameras=[])
    await run(hub2, FakeDav(), store, cfg, now=NOW + timedelta(minutes=1))
    assert hub2.camera_windows[2][0] == NOW - timedelta(hours=3, minutes=10)
    assert store.status(clips[2].key) == "uploaded"


async def test_all_searches_failing_is_a_hub_error(cfg, store):
    store.set_known_cameras({0: GARAGE, 1: BACKYARD})
    store.set_camera_watermark(0, NOW - timedelta(hours=1))
    hub = FakeHub([cam_clip(GARAGE, 5)], cameras=[], fail_search={0, 1})
    with pytest.raises(HubError, match="search channel"):
        await run(hub, FakeDav(), store, cfg)
    assert sorted(hub.searched) == [0, 1] and hub.downloads == []
    assert store.get_camera_watermark(0) == NOW - timedelta(hours=1)
    assert store.get_camera_watermark(1) is None


async def test_migration_starts_each_camera_from_its_newest_uploaded_clip(cfg, store):
    # 0.3.0 left a global watermark that ran past Backyard and Living Room while they were not searched.
    # A camera's first search reaches back to the backfill limit: that covers its newest upload and
    # every gap before it (only the clips not on the VPS yet are uploaded).
    floor = NOW - timedelta(days=cfg.backfill_days)
    store.set_watermark(NOW - timedelta(minutes=2))
    store.set_known_cameras(FOUR)
    old_backyard = cam_clip(BACKYARD, 60 * 30)  # 21 Nov 15:00-ish: its last upload before it dropped out
    older_backyard = cam_clip(BACKYARD, 60 * 40)
    old_garage = cam_clip(GARAGE, 30)
    for c in (old_backyard, older_backyard, old_garage):
        store.mark_uploaded(c.key, f"x/{c.key}")
    missed = cam_clip(BACKYARD, 60 * 12)  # never searched by 0.3.0
    hub = FakeHub([old_backyard, older_backyard, old_garage, missed], cameras=[])
    result = await run(hub, FakeDav(), store, cfg)
    assert hub.camera_windows == {ch: (floor, NOW) for ch in FOUR}
    assert store.status(missed.key) == "uploaded"
    assert result.uploaded == 1 and result.skipped == 3  # no duplicates
    assert all(store.get_camera_watermark(ch) == NOW - timedelta(seconds=20) for ch in FOUR)
    # Afterwards each camera follows its own watermark.
    hub2 = FakeHub([], cameras=[])
    await run(hub2, FakeDav(), store, cfg, now=NOW + timedelta(minutes=1))
    assert hub2.camera_windows == {ch: (NOW - timedelta(seconds=20, minutes=10), NOW + timedelta(minutes=1))
                                   for ch in FOUR}


async def test_migration_recovers_a_gap_that_ended_before_the_upgrade(cfg, store):
    # Under 0.3.0 Living Room dropped out (02:01-06:00) and came back; its later clips were uploaded.
    store.set_known_cameras({2: LIVING})
    gap = cam_clip(LIVING, 60 * 5)
    after = cam_clip(LIVING, 60)
    store.mark_uploaded(after.key, "x/after")
    store.set_watermark(NOW - timedelta(minutes=2))
    hub = FakeHub([gap, after], cameras=[])
    await run(hub, FakeDav(), store, cfg)
    assert store.status(gap.key) == "uploaded" and hub.downloads == [gap.key]


async def test_migration_recovers_a_retry_pending_when_the_camera_dropped_out(cfg, store):
    # A Backyard clip failed (to retry), a newer one uploaded, then a reconnect dropped Backyard.
    store.set_known_cameras({1: BACKYARD})
    pending, newer = cam_clip(BACKYARD, 60 * 3), cam_clip(BACKYARD, 60 * 3 - 30)
    store.record_failure(pending.key, "download broke", cfg.max_attempts)
    store.mark_uploaded(newer.key, "x/newer")
    store.set_watermark(NOW - timedelta(minutes=2))
    hub = FakeHub([pending, newer], cameras=[])
    await run(hub, FakeDav(), store, cfg)
    assert store.status(pending.key) == "uploaded"


async def test_migration_never_skips_a_pending_clip_held_by_the_global_watermark(cfg, store):
    # 0.3.0 held the global watermark at a Garage clip still to retry; a newer one was uploaded.
    pending = cam_clip(GARAGE, 60)
    newer = cam_clip(GARAGE, 20)
    store.set_watermark(pending.start)
    store.record_failure(pending.key, "download broke", cfg.max_attempts)
    store.mark_uploaded(newer.key, "x/newer")
    store.set_known_cameras({0: GARAGE})
    hub = FakeHub([pending, newer], cameras=[])
    await run(hub, FakeDav(), store, cfg)
    assert hub.camera_windows[0][0] <= pending.start - timedelta(minutes=10)  # first search: back to the floor
    assert store.status(pending.key) == "uploaded"


async def test_regression_login_saw_only_channel_0_but_all_known_cameras_uploaded(cfg, store):
    # 22 Nov 02:01: reolink-aio's login listed channels [0] only, so 0.3.0 searched only the Garage.
    from fake_reolink import BACKYARD as BY_UID, FRONT as FD_UID, GARAGE as GA_UID, LIVING as LR_UID
    from fake_reolink import FakeHost, slot

    from camera_uploader.hub import ReolinkHub

    store.set_known_cameras({0: Camera(0, "Garage", GA_UID), 1: Camera(1, "Backyard", BY_UID),
                             2: Camera(2, "Living Room", LR_UID), 3: Camera(3, "Front door", FD_UID)})
    recs = {ch: [(NOW - timedelta(minutes=10 + ch), NOW - timedelta(minutes=10 + ch) + timedelta(seconds=30))]
            for ch in range(4)}
    # Now: Backyard offline with no name/uid, Living Room offline, Front door awake again.
    status = [slot(0, 1, "Garage", GA_UID), slot(1), slot(2, 0, "Living Room", LR_UID),
              slot(3, 1, "Front door", FD_UID), slot(4)]
    host = FakeHost(channels=[0], status=status, recordings=recs)
    hub = ReolinkHub(cfg)
    hub._host = host
    dav = FakeDav()
    result = await run(hub, dav, store, cfg)
    assert {ch for ch, *_ in host.baichuan.searches} == {0, 1, 2, 3}
    assert result.uploaded == 4 and result.failed == 0
    folders = sorted(p.split("/")[0] for p in dav.files if p.endswith(".mp4"))
    assert folders == ["Backyard", "Front door", "Garage", "Living Room"]
    sidecar_ids = sorted(json.loads(v)["camera_id"] for p, v in dav.files.items() if p.endswith(".json"))
    assert sidecar_ids == sorted([GA_UID, BY_UID, LR_UID, FD_UID])
    assert result.cameras_offline == ["Backyard", "Living Room"]


async def test_failing_camera_reported_in_cycle_result(cfg, store, caplog):
    store.set_known_cameras(FOUR)
    store.set_camera_watermark(1, NOW - timedelta(days=2))
    hub = FakeHub([], cameras=[], fail_search={1, 2})
    result = await run(hub, FakeDav(), store, cfg)
    assert result.cameras_search_failing == ["Backyard", "Living Room"]
    # Behind by more than a day: an error, not just a warning (clips age out after backfill_days).
    errors = [r.getMessage() for r in caplog.records if r.levelname == "ERROR"]
    assert any("Backyard" in m for m in errors) and not any("Living Room" in m for m in errors)
    result = await run(FakeHub([], cameras=[]), FakeDav(), store, cfg, now=NOW + timedelta(minutes=1))
    assert result.cameras_search_failing == []


async def test_link_failure_stops_searching_but_uploads_what_was_found(cfg, store):
    store.set_known_cameras(FOUR)
    for ch in FOUR:
        store.set_camera_watermark(ch, NOW - timedelta(minutes=30))
    clips = [cam_clip(c, 10 + c.channel) for c in FOUR.values()]
    hub = FakeHub(clips, cameras=[])
    hub.transport_fail = {1}
    with pytest.raises(HubError, match="channel 1") as info:
        await run(hub, FakeDav(), store, cfg)
    assert not isinstance(info.value, CycleAborted)
    assert hub.searched == [0, 1]  # no more searches on a link that just failed
    assert store.status(clips[0].key) == "uploaded"
    assert store.get_camera_watermark(0) == NOW - timedelta(seconds=cfg.settle_seconds)
    assert all(store.get_camera_watermark(ch) == NOW - timedelta(minutes=30) for ch in (1, 2, 3))
    # Nothing searched before the failure: the error at once.
    other = StateStore(cfg.tmp_dir.parent / "other.db")
    other.set_known_cameras(FOUR)
    hub = FakeHub([], cameras=[])
    hub.transport_fail = {0, 1, 2, 3}
    with pytest.raises(HubError):
        await run(hub, FakeDav(), other, cfg)
    assert len(hub.searched) == 1


async def test_lagging_cameras_are_searched_last(cfg, store):
    # A camera stuck failing (oldest watermark) or on its first search must not hold the others back.
    store.set_known_cameras(FOUR)
    store.set_camera_watermark(0, NOW - timedelta(hours=5))
    store.set_camera_watermark(2, NOW - timedelta(minutes=1))
    store.set_camera_watermark(3, NOW - timedelta(minutes=2))
    hub = FakeHub([], cameras=[])
    await run(hub, FakeDav(), store, cfg)
    assert hub.searched == [2, 3, 0, 1]


async def test_long_windows_are_searched_in_slices(cfg, store):
    from camera_uploader.cycle import SEARCH_SLICE

    store.set_known_cameras({1: BACKYARD})
    floor = NOW - timedelta(days=cfg.backfill_days)
    clips = [cam_clip(BACKYARD, 60 * 24 * d + 5) for d in (0, 3, 29)]
    hub = FakeHub(clips, cameras=[])
    hub.truncate_over = SEARCH_SLICE / 4  # e.g. a very busy camera: slices are split further
    result = await run(hub, FakeDav(), store, cfg)
    assert hub.windows[0] == (floor, floor + SEARCH_SLICE)  # cut short: searched again in halves
    done = [w for w in hub.windows if w[1] - w[0] <= SEARCH_SLICE / 4]
    assert done[0][0] == floor and done[-1][1] == NOW
    assert all(a[1] == b[0] for a, b in zip(done, done[1:]))  # contiguous
    assert result.uploaded == 3 and len(hub.downloads) == 3  # a clip on a boundary is not uploaded twice


async def test_failed_slice_holds_the_watermark_at_its_start(cfg, store):
    from camera_uploader.cycle import SEARCH_SLICE

    store.set_known_cameras({1: BACKYARD})
    early = cam_clip(BACKYARD, 60 * 24 * 20)
    late = cam_clip(BACKYARD, 60 * 24 * 2)
    hub = FakeHub([early, late], cameras=[])
    hub.fail_at = NOW - timedelta(days=10)
    result = await run(hub, FakeDav(), store, cfg)
    assert result.uploaded == 1 and store.status(early.key) == "uploaded"  # what was searched is uploaded
    failed = [s for s, e in hub.windows if s <= hub.fail_at <= e][0]
    assert hub.windows[-1][0] == failed  # nothing after the failed slice was searched
    assert store.get_camera_watermark(1) == failed
    assert result.cameras_search_failing == ["Backyard"]
    hub.fail_at, before = None, len(hub.windows)
    await run(hub, FakeDav(), store, cfg, now=NOW + timedelta(minutes=1))
    assert store.status(late.key) == "uploaded"
    retry = hub.windows[before:]
    assert all(e - s <= SEARCH_SLICE for s, e in retry)
    assert retry[0][0] == failed - timedelta(minutes=10) and retry[-1][1] == NOW + timedelta(minutes=1)


async def test_one_cameras_failing_downloads_do_not_stall_others(cfg, store):
    # Backyard is offline and its recordings cannot be downloaded; Garage's can.
    store.set_known_cameras({0: GARAGE, 1: BACKYARD})
    b1, b2, b3 = cam_clip(BACKYARD, 15), cam_clip(BACKYARD, 14), cam_clip(BACKYARD, 13)
    g1, g2 = cam_clip(GARAGE, 12), cam_clip(GARAGE, 11)
    hub = FakeHub([b1, b2, b3, g1, g2], cameras=[], fail_download={b1.key, b2.key, b3.key})
    result = await run(hub, FakeDav(), store, cfg)
    assert hub.downloads == [b1.key, b2.key, g1.key, g2.key]  # Backyard benched after 2 in a row
    assert result.uploaded == 2 and result.failed == 2
    assert store.status(b3.key) == "new"
    assert store.get_camera_watermark(1) == b1.start  # its clips are still to do
    assert store.get_camera_watermark(0) == NOW - timedelta(seconds=cfg.settle_seconds)
