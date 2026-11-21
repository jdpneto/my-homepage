import json
from datetime import datetime, timedelta, timezone

import pytest

from camera_uploader.heartbeat import Heartbeat
from camera_uploader.webdav import TransientError

T0 = datetime(2026, 11, 20, 12, 0, 0, tzinfo=timezone(timedelta(hours=1)))


class Dav:
    def __init__(self, fail=False):
        self.puts, self.fail = [], fail

    async def put_bytes(self, path, data, content_type):
        if self.fail:
            raise TransientError("down")
        self.puts.append((path, json.loads(data), content_type))


async def test_first_send_immediately():
    hb, dav = Heartbeat(300), Dav()
    hb.record(True, None, 4, T0)
    assert await hb.maybe_send(dav, T0) is True
    path, body, ctype = dav.puts[0]
    assert path == "heartbeat.json" and ctype == "application/json"
    assert body["time"] == "2026-11-20T12:00:00+01:00"
    assert body["hub_ok"] is True and body["hub_error_since"] is None and body["cameras"] == 4
    assert body["uploader_version"]


async def test_rate_limited_to_interval():
    hb, dav = Heartbeat(300), Dav()
    hb.record(True, None, 4, T0)
    await hb.maybe_send(dav, T0)
    hb.record(True, None, 4, T0 + timedelta(seconds=30))
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=30)) is False
    hb.record(True, None, 4, T0 + timedelta(seconds=301))
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=301)) is True
    assert len(dav.puts) == 2


async def test_state_change_sends_early():
    hb, dav = Heartbeat(300), Dav()
    hb.record(True, None, 4, T0)
    await hb.maybe_send(dav, T0)
    hb.record(False, "HubError: no route", None, T0 + timedelta(seconds=30))
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=30)) is True
    assert dav.puts[-1][1]["hub_ok"] is False


async def test_hub_error_since_kept_across_failures_and_cleared_on_success():
    hb, dav = Heartbeat(0), Dav()
    hb.record(False, "HubError: a", None, T0)
    hb.record(False, "HubError: b", None, T0 + timedelta(minutes=10))
    await hb.maybe_send(dav, T0 + timedelta(minutes=10))
    body = dav.puts[-1][1]
    assert body["hub_error_since"] == "2026-11-20T12:00:00+01:00"
    assert body["last_error"] == "HubError: b"
    hb.record(True, None, 4, T0 + timedelta(minutes=20))
    await hb.maybe_send(dav, T0 + timedelta(minutes=20))
    assert dav.puts[-1][1]["hub_error_since"] is None
    assert dav.puts[-1][1]["last_error"] is None


async def test_non_hub_error_keeps_hub_state():
    hb, dav = Heartbeat(0), Dav()
    hb.record(True, None, 4, T0)
    hb.record(None, "TransientError: vps 502", None, T0 + timedelta(minutes=1))
    await hb.maybe_send(dav, T0 + timedelta(minutes=1))
    body = dav.puts[-1][1]
    assert body["hub_ok"] is True and body["last_error"] == "TransientError: vps 502" and body["cameras"] == 4


async def test_send_failure_is_swallowed_and_retried_next_time():
    hb = Heartbeat(300)
    hb.record(True, None, 4, T0)
    assert await hb.maybe_send(Dav(fail=True), T0) is False
    ok = Dav()
    assert await hb.maybe_send(ok, T0 + timedelta(seconds=30)) is True


def test_payload_has_no_password():
    hb = Heartbeat(300)
    hb.record(False, "HubAuthError: hub rejected the username/password", None, T0)
    text = json.dumps(hb.payload(T0))
    assert "secret" not in text and "password\":" not in text


async def test_payload_has_last_ok_and_skew():
    hb, dav = Heartbeat(0), Dav()
    hb.started(T0)
    hb.record(True, None, 4, T0)
    hb.set_skew(-12.4)
    await hb.maybe_send(dav, T0)
    body = dav.puts[-1][1]
    assert body["last_ok"] == "2026-11-20T12:00:00+01:00"
    assert body["hub_clock_skew_seconds"] == -12


async def test_last_ok_starts_at_start_and_moves_on_progress():
    hb, dav = Heartbeat(0), Dav()
    hb.started(T0)
    hb.record(False, "HubError: x", None, T0 + timedelta(minutes=5))
    await hb.maybe_send(dav, T0 + timedelta(minutes=5))
    assert dav.puts[-1][1]["last_ok"] == "2026-11-20T12:00:00+01:00"
    hb.progress(T0 + timedelta(minutes=7))
    await hb.maybe_send(dav, T0 + timedelta(minutes=8))
    assert dav.puts[-1][1]["last_ok"] == "2026-11-20T12:07:00+01:00"


async def test_suppressed_heartbeat_sends_nothing():
    hb, dav = Heartbeat(0), Dav()
    hb.record(True, None, 4, T0)
    hb.suppress(True)  # e.g. WebDAV rejected the password: a PUT would only add a failed login
    assert await hb.maybe_send(dav, T0) is False
    assert dav.puts == []
    hb.suppress(False)
    assert await hb.maybe_send(dav, T0) is True


async def test_payload_has_mode_and_change_sends_early():
    hb, dav = Heartbeat(300), Dav()
    hb.record(True, None, 4, T0)
    assert hb.payload(T0)["mode"] is None
    hb.set_mode("home")
    await hb.maybe_send(dav, T0)
    assert dav.puts[-1][1]["mode"] == "home"
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=10)) is False  # unchanged: rate-limited
    hb.set_mode("away")
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=20)) is True  # changed: sent at once
    assert dav.puts[-1][1]["mode"] == "away"


async def test_mode_request_result_sent_at_once():
    hb, dav = Heartbeat(300), Dav()
    hb.record(True, None, 4, T0)
    assert hb.payload(T0)["mode_request"] is None
    await hb.maybe_send(dav, T0)
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=10)) is False  # nothing new: rate-limited
    result = {"id": "req-1", "status": "applied", "error": None, "at": "2026-11-20T12:00:20+01:00"}
    hb.set_mode_request_result(result)
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=20)) is True  # new result: sent at once
    assert dav.puts[-1][1]["mode_request"] == result
    hb.set_mode_request_result(dict(result))  # the same result again: no extra send
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=30)) is False
    failed = {"id": "req-2", "status": "failed", "error": "hub refused", "at": "2026-11-20T12:00:40+01:00"}
    hb.set_mode_request_result(failed)
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=40)) is True
    assert dav.puts[-1][1]["mode_request"] == failed


async def test_sent_state_is_what_was_sent():
    # A result set while a (background) heartbeat PUT is in flight was not in that PUT:
    # the next maybe_send must still send it, not think it already went out.
    hb = Heartbeat(300)
    hb.record(True, None, 4, T0)
    result = {"id": "req-1", "status": "applied", "error": None, "at": "2026-11-20T12:00:20+01:00"}

    class SlowDav(Dav):
        async def put_bytes(self, path, data, content_type):
            await super().put_bytes(path, data, content_type)
            if len(self.puts) == 1:
                hb.set_mode_request_result(result)  # the cycle reports while this PUT is in flight
                hb.set_mode("away")

    dav = SlowDav()
    assert await hb.maybe_send(dav, T0) is True
    assert dav.puts[0][1]["mode_request"] is None
    assert await hb.maybe_send(dav, T0 + timedelta(seconds=1)) is True
    assert dav.puts[1][1]["mode_request"] == result and dav.puts[1][1]["mode"] == "away"
