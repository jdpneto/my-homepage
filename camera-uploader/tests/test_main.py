import logging
from datetime import datetime

import pytest

from camera_uploader.config import Config
from camera_uploader.hub import HubError
from camera_uploader.main import backoff_seconds, prepare_tmp, run_forever, run_once
from camera_uploader.webdav import AuthError, QuotaFullError, TransientError, WebDavError

NOW = datetime(2026, 11, 18, 21, 0, 0)


class Stop(Exception):
    pass


class FakeHub:
    instances: list["FakeHub"] = []

    def __init__(self, fail_list=False, fail_connect=False):
        self.fail_list, self.fail_connect = fail_list, fail_connect
        self.connected = self.closed = False
        FakeHub.instances.append(self)

    async def connect(self):
        if self.fail_connect:
            raise HubError("login refused")
        self.connected = True

    async def refresh_cameras(self, known):
        from camera_uploader.model import Camera

        return {**known, 0: Camera(0, "Garage", "uid0", online=True)}

    async def list_clips(self, camera, start, end):
        if self.fail_list:
            raise HubError("session expired")
        return []

    async def info(self):
        return {"model": "m", "firmware": "f"}

    async def mode(self):
        return "away"

    async def now(self):
        return NOW

    async def close(self):
        self.closed = True


class FakeDav:
    def __init__(self, exists_exc=None):
        self.exists_exc = exists_exc

    async def get_text(self, path):
        if self.exists_exc:
            raise self.exists_exc
        return "camera clips live on the 15 GB volume - do not delete\n"

    async def put_bytes(self, path, data, content_type):
        self.puts = getattr(self, "puts", [])
        self.puts.append(path)

    async def aclose(self):
        pass


def sleeper(limit):
    delays = []

    async def sleep(seconds):
        delays.append(seconds)
        if len(delays) >= limit:
            raise Stop()

    return sleep, delays


@pytest.fixture
def cfg(tmp_path):
    return Config(hub_host="h", hub_user="admin", hub_password="hub-secret-pw", webdav_url="https://x/",
                  webdav_user="cameras", webdav_password="dav-secret-pw", state_path=tmp_path / "s.db",
                  tmp_dir=tmp_path / "tmp", log_path=tmp_path / "log" / "uploader.log", poll_seconds=30)


@pytest.fixture(autouse=True)
def _reset():
    FakeHub.instances.clear()


@pytest.mark.parametrize("error,consecutive,expected", [
    (AuthError("401"), 1, 900),
    (QuotaFullError("507"), 1, 600),
    (HubError("x"), 1, 30),
    (HubError("x"), 2, 60),
    (TransientError("x"), 3, 120),
    (TransientError("x"), 10, 300),
    (WebDavError("marker"), 1, 300),
    (RuntimeError("bug"), 1, 300),
])
def test_backoff_seconds(error, consecutive, expected):
    assert backoff_seconds(error, consecutive) == expected


async def test_success_sleeps_poll_seconds(cfg):
    sleep, delays = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    assert delays == [30, 30]
    assert len(FakeHub.instances) == 1  # one session reused


async def test_hub_error_recreates_session(cfg):
    hubs = iter([FakeHub(fail_list=True), FakeHub()])
    sleep, delays = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: next(hubs), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    first, second = FakeHub.instances
    assert first.closed and second.connected
    assert delays == [30, 30]  # backoff 30 after 1st failure, then normal poll


async def test_connect_failure_backs_off_and_retries(cfg):
    hubs = iter([FakeHub(fail_connect=True), FakeHub(fail_connect=True), FakeHub()])
    sleep, delays = sleeper(3)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: next(hubs), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    assert delays == [30, 60, 30]


async def test_auth_error_backs_off_15_minutes(cfg):
    sleep, delays = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: FakeDav(AuthError("401")), sleep=sleep,
                          clock=lambda: NOW)
    assert delays == [900]


async def test_hub_closed_on_exit(cfg):
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    assert FakeHub.instances[0].closed


def test_tmp_dir_emptied_at_start(cfg):
    cfg.tmp_dir.mkdir(parents=True)
    (cfg.tmp_dir / "left-over.raw.mp4").write_bytes(b"x" * 10)
    (cfg.tmp_dir / "sub").mkdir()
    prepare_tmp(cfg.tmp_dir)
    assert cfg.tmp_dir.is_dir() and list(cfg.tmp_dir.iterdir()) == []


async def test_run_forever_empties_tmp(cfg):
    cfg.tmp_dir.mkdir(parents=True)
    (cfg.tmp_dir / "left-over.mp4").write_bytes(b"x")
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    assert not (cfg.tmp_dir / "left-over.mp4").exists()


async def test_password_never_logged(cfg, caplog):
    caplog.set_level(logging.DEBUG)
    sleep, _ = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(fail_connect=True), lambda c: FakeDav(AuthError("HTTP 401")),
                          sleep=sleep, clock=lambda: NOW)
    assert "hub-secret-pw" not in caplog.text and "dav-secret-pw" not in caplog.text
    assert caplog.text  # something was logged


async def test_run_once_returns_exit_code(cfg):
    assert await run_once(cfg, lambda c: FakeHub(), lambda c: FakeDav(), clock=lambda: NOW) == 0
    assert await run_once(cfg, lambda c: FakeHub(fail_connect=True), lambda c: FakeDav(), clock=lambda: NOW) == 1
    assert all(h.closed for h in FakeHub.instances)


async def test_sigterm_shuts_down_cleanly(cfg):
    import asyncio
    import os
    import signal

    from camera_uploader.main import serve

    async def slow_sleep(seconds):
        await asyncio.sleep(10)

    async def send_term():
        await asyncio.sleep(0.2)
        os.kill(os.getpid(), signal.SIGTERM)

    asyncio.get_running_loop().create_task(send_term())
    await serve(cfg, lambda c: FakeHub(), lambda c: FakeDav(), sleep=slow_sleep, clock=lambda: NOW)
    assert FakeHub.instances[0].closed


async def test_marker_missing_backs_off_without_resetting_hub(cfg, caplog):
    from camera_uploader.cycle import VolumeMarkerMissing

    sleep, delays = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: FakeDav(VolumeMarkerMissing("missing")),
                          sleep=sleep, clock=lambda: NOW)
    assert delays == [300, 300]
    assert len(FakeHub.instances) == 1          # same hub session kept
    assert "Traceback" not in caplog.text


def test_backoff_hub_auth_error_is_15_minutes():
    from camera_uploader.hub import HubAuthError
    assert backoff_seconds(HubAuthError("bad password"), 1) == 900
    assert backoff_seconds(HubAuthError("bad password"), 5) == 900


async def test_cycle_uses_hub_clock_and_warns_on_skew(cfg, caplog):
    from datetime import timedelta

    hub_time = NOW - timedelta(hours=1)

    class SkewedHub(FakeHub):
        windows = []

        async def now(self):
            return hub_time

        async def list_clips(self, camera, start, end):
            SkewedHub.windows.append((start, end))
            return []

    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: SkewedHub(), lambda c: FakeDav(), sleep=sleep, clock=lambda: NOW)
    assert SkewedHub.windows[-1][1] == hub_time  # (a first search is in slices, oldest first)
    assert "clock" in caplog.text.lower()


def test_setup_logging_silences_udp_spam_and_keeps_launchd_stderr_quiet(tmp_path, monkeypatch):
    import sys as _sys

    from camera_uploader.main import setup_logging

    monkeypatch.setattr(_sys.stderr, "isatty", lambda: False)
    setup_logging(tmp_path / "log" / "uploader.log")
    # reolink-aio logs a WARNING every 0.5 s while the hub is unreachable
    assert logging.getLogger("reolink_aio.baichuan.udp_protocol").getEffectiveLevel() >= logging.ERROR
    console = [h for h in logging.getLogger().handlers if not hasattr(h, "baseFilename")]
    assert console and all(h.level >= logging.ERROR for h in console)  # launchd.err.log is never rotated


async def test_run_forever_sends_heartbeat_even_when_hub_down(cfg):
    import json as _json

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.puts = []

        async def put_bytes(self, path, data, content_type):
            self.puts.append((path, _json.loads(data)))

    dav = RecordingDav()
    sleep, _ = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(fail_connect=True), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    beats = [body for path, body in dav.puts if path == "heartbeat.json"]
    assert beats, "no heartbeat sent"
    assert beats[0]["hub_ok"] is False
    assert "login refused" in beats[0]["last_error"]
    assert beats[0]["hub_error_since"] is not None


async def test_run_forever_heartbeat_ok_after_good_cycle(cfg):
    import json as _json

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.puts = []

        async def put_bytes(self, path, data, content_type):
            self.puts.append((path, _json.loads(data)))

    dav = RecordingDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    beats = [body for path, body in dav.puts if path == "heartbeat.json"]
    assert beats and beats[0]["hub_ok"] is True and beats[0]["last_error"] is None
    assert beats[-1]["mode"] == "away"


async def test_no_heartbeat_after_webdav_auth_error(cfg):
    dav = FakeDav(AuthError("HTTP 401"))
    sleep, _ = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert getattr(dav, "puts", []) == []  # a heartbeat would just be another failed login


async def test_heartbeat_bug_never_stops_uploads():
    from camera_uploader.heartbeat import Heartbeat

    class Broken:
        async def put_bytes(self, *a):
            raise RuntimeError("unexpected")

    hb = Heartbeat(0)
    hb.record(True, None, 4, NOW.astimezone())
    assert await hb.maybe_send(Broken(), NOW.astimezone()) is False


async def test_cycle_aborted_is_not_reported_as_hub_down(cfg):
    import json as _json
    from camera_uploader.cycle import CycleAborted

    class AbortingHub(FakeHub):
        async def list_clips(self, camera, start, end):
            raise CycleAborted("remux: cannot run ffmpeg")

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.bodies = []

        async def put_bytes(self, path, data, content_type):
            self.bodies.append(_json.loads(data))

    dav = RecordingDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: AbortingHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert dav.bodies and dav.bodies[0]["hub_ok"] is not False
    assert "ffmpeg" in dav.bodies[0]["last_error"]


async def test_heartbeat_time_always_has_an_offset(cfg):
    import json as _json
    from datetime import datetime as _dt

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.bodies = []

        async def put_bytes(self, path, data, content_type):
            self.bodies.append(_json.loads(data))

    dav = RecordingDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: FakeHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    for key in ("time", "last_ok"):
        assert _dt.fromisoformat(dav.bodies[0][key]).tzinfo is not None, key


async def test_skew_reported_from_hub_clock(cfg):
    import json as _json
    from datetime import timedelta as _td

    class SkewedHub(FakeHub):
        async def now(self):
            return NOW - _td(days=400)

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.bodies = []

        async def put_bytes(self, path, data, content_type):
            self.bodies.append(_json.loads(data))

    dav = RecordingDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: SkewedHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert dav.bodies[0]["hub_clock_skew_seconds"] == -400 * 86400


async def test_mode_request_result_in_heartbeat_and_kept_across_restart(cfg):
    import json as _json
    from datetime import timedelta

    requested = NOW.astimezone() - timedelta(minutes=1)  # run_forever's clock is NOW (naive, local)
    request = _json.dumps({"id": "req-1", "mode": "home", "requested_at": requested.isoformat(),
                           "expires_at": (requested + timedelta(minutes=5)).isoformat()})

    class SwitchingHub(FakeHub):
        current = "away"
        switched: list[str] = []

        async def mode(self):
            return SwitchingHub.current

        async def switch_mode(self, name, not_after=None):
            SwitchingHub.switched.append(name)
            SwitchingHub.current = name

    class RequestDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.beats = []

        async def get_text(self, path):
            return request if path == "mode-request.json" else await super().get_text(path)

        async def put_bytes(self, path, data, content_type):
            if path == "heartbeat.json":
                self.beats.append(_json.loads(data))

    dav = RequestDav()
    sleep, _ = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: SwitchingHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert SwitchingHub.switched == ["home"]  # two cycles, one switch
    last = dav.beats[-1]
    assert last["mode"] == "home"
    assert last["mode_request"]["id"] == "req-1" and last["mode_request"]["status"] == "applied"

    # Restarted: the result is still reported (the page must not flip to "expired"), nothing re-applied.
    dav2 = RequestDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: SwitchingHub(), lambda c: dav2, sleep=sleep, clock=lambda: NOW)
    assert SwitchingHub.switched == ["home"]
    assert dav2.beats[-1]["mode_request"]["status"] == "applied"


async def test_mode_request_result_sent_before_the_cycle_ends(cfg):
    import json as _json
    from datetime import timedelta

    requested = NOW.astimezone() - timedelta(minutes=1)
    request = _json.dumps({"id": "req-7", "mode": "away", "requested_at": requested.isoformat(),
                           "expires_at": (requested + timedelta(minutes=5)).isoformat()})
    seen_during_cycle = []

    class Dav(FakeDav):
        beats: list = []

        async def get_text(self, path):
            return request if path == "mode-request.json" else await super().get_text(path)

        async def put_bytes(self, path, data, content_type):
            if path == "heartbeat.json":
                Dav.beats.append(_json.loads(data))

    class SlowCycleHub(FakeHub):
        current = "home"

        async def mode(self):
            return SlowCycleHub.current

        async def switch_mode(self, name, not_after=None):
            SlowCycleHub.current = name

        async def list_clips(self, camera, start, end):
            # Still in the cycle (a long catch-up would follow): the page must already see the result.
            seen_during_cycle.extend(Dav.beats)
            return []

    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: SlowCycleHub(), lambda c: Dav(), sleep=sleep, clock=lambda: NOW)
    reported = [b for b in seen_during_cycle if b.get("mode_request")]
    assert reported, seen_during_cycle
    assert reported[-1]["mode_request"]["id"] == "req-7" and reported[-1]["mode_request"]["status"] == "applied"
    assert reported[-1]["mode"] == "away"


async def test_heartbeat_reports_known_and_offline_cameras(cfg):
    import json as _json

    from camera_uploader.model import Camera
    from camera_uploader.state import StateStore

    store = StateStore(cfg.state_path)
    store.set_known_cameras({ch: Camera(ch, n, f"uid{ch}") for ch, n in
                             enumerate(("Garage", "Backyard", "Living Room", "Front door"))})
    store.close()

    class OneOnlineHub(FakeHub):
        refreshes = 0

        async def refresh_cameras(self, known):
            OneOnlineHub.refreshes += 1
            if OneOnlineHub.refreshes > 1:
                return known  # the second refresh fails: the latest status stands
            return {ch: Camera(c.channel, c.name, c.uid, online=(ch == 0)) for ch, c in known.items()}

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.bodies = []

        async def put_bytes(self, path, data, content_type):
            if path == "heartbeat.json":
                self.bodies.append(_json.loads(data))

    dav = RecordingDav()
    sleep, _ = sleeper(2)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: OneOnlineHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert dav.bodies[0]["cameras"] == 4  # known cameras, not reolink-aio's login-time channel list
    assert dav.bodies[0]["cameras_offline"] == ["Backyard", "Front door", "Living Room"]
    assert dav.bodies[0]["cameras_search_failing"] == []
    assert OneOnlineHub.refreshes == 2


async def test_heartbeat_reports_cameras_whose_search_fails(cfg):
    import json as _json

    from camera_uploader.hub import CameraSearchError
    from camera_uploader.model import Camera

    class LivingRoomFailsHub(FakeHub):
        async def refresh_cameras(self, known):
            return {0: Camera(0, "Garage", "uid0", online=True), 2: Camera(2, "Living Room", "uid2", online=True)}

        async def list_clips(self, camera, start, end):
            if camera.channel == 2:
                raise CameraSearchError("search channel 2 (Living Room): ApiError: busy")
            return []

    class RecordingDav(FakeDav):
        def __init__(self):
            super().__init__()
            self.bodies = []

        async def put_bytes(self, path, data, content_type):
            if path == "heartbeat.json":
                self.bodies.append(_json.loads(data))

    dav = RecordingDav()
    sleep, _ = sleeper(1)
    with pytest.raises(Stop):
        await run_forever(cfg, lambda c: LivingRoomFailsHub(), lambda c: dav, sleep=sleep, clock=lambda: NOW)
    assert dav.bodies[0]["cameras_search_failing"] == ["Living Room"]
    assert dav.bodies[0]["hub_ok"] is True  # the hub answered; one camera is the problem
