"""heartbeat.json on the camera share, so the VPS can tell "uploader dead" from "no motion"."""

from __future__ import annotations

import json
import logging
from datetime import datetime

from . import __version__

log = logging.getLogger(__name__)

PATH = "heartbeat.json"


class Heartbeat:
    def __init__(self, interval_seconds: float = 300):
        self._interval = interval_seconds
        self._hub_ok: bool | None = None
        self._hub_error_since: datetime | None = None
        self._last_error: str | None = None
        self._cameras: int | None = None
        self._cameras_offline: list[str] | None = None
        self._cameras_search_failing: list[str] | None = None
        self._last_sent: datetime | None = None
        self._sent_hub_ok: bool | None = None
        self._last_ok: datetime | None = None
        self._skew: float | None = None
        self._suppressed = False
        self._mode: str | None = None
        self._sent_mode: str | None = None
        self._mode_request: dict | None = None
        self._sent_mode_request: dict | None = None

    def set_mode(self, mode: str | None) -> None:
        """The hub's active mode after a cycle (None = unknown). A change is sent at once."""
        self._mode = mode

    def set_mode_request_result(self, result: dict | None) -> None:
        """The outcome of the last remote mode request ({"id","status","error","at"}). A new one is sent at once."""
        self._mode_request = dict(result) if result else None

    def set_cameras_offline(self, names: list[str] | None) -> None:
        """Known cameras the hub's latest status said are offline. None (no status this cycle) keeps
        the previous list; it is null only until the first status."""
        if names is not None:
            self._cameras_offline = sorted(names)

    def set_cameras_search_failing(self, names: list[str] | None) -> None:
        """Known cameras whose search failed in the last cycle that ran (their clips wait, and age out
        after backfill_days). None (the cycle failed) keeps the previous list."""
        if names is not None:
            self._cameras_search_failing = sorted(names)

    def suppress(self, on: bool) -> None:
        """While on, nothing is sent (e.g. after a WebDAV 401: a PUT would add a failed login)."""
        self._suppressed = on

    def started(self, now: datetime) -> None:
        """last_ok starts at process start, so "nothing has worked since start" is detectable."""
        if self._last_ok is None:
            self._last_ok = now

    def progress(self, now: datetime) -> None:
        """Something succeeded (a cycle, or one clip of a long catch-up cycle)."""
        self._last_ok = now

    def set_skew(self, seconds: float | None) -> None:
        self._skew = seconds

    def record(self, hub_ok: bool | None, error: str | None, cameras: int | None, now: datetime) -> None:
        """After a cycle. hub_ok=None means "not a hub problem": the hub state is left as it was."""
        if hub_ok is True:
            self._hub_ok, self._hub_error_since = True, None
            if error is None:
                self._last_ok = now
        elif hub_ok is False:
            if self._hub_ok is not False or self._hub_error_since is None:
                self._hub_error_since = now
            self._hub_ok = False
        self._last_error = error
        if cameras is not None:
            self._cameras = cameras

    def payload(self, now: datetime) -> dict:
        return {
            "time": now.isoformat(timespec="seconds"),
            "uploader_version": __version__,
            "hub_ok": self._hub_ok,
            "hub_error_since": self._hub_error_since.isoformat(timespec="seconds") if self._hub_error_since else None,
            "cameras": self._cameras,
            "cameras_offline": list(self._cameras_offline) if self._cameras_offline is not None else None,
            "cameras_search_failing": (list(self._cameras_search_failing)
                                       if self._cameras_search_failing is not None else None),
            "last_error": self._last_error,
            "last_ok": self._last_ok.isoformat(timespec="seconds") if self._last_ok else None,
            "hub_clock_skew_seconds": round(self._skew) if self._skew is not None else None,
            "mode": self._mode,
            "mode_request": self._mode_request,
        }

    async def maybe_send(self, dav, now: datetime) -> bool:
        if self._suppressed:
            return False
        due = (self._last_sent is None
               or (now - self._last_sent).total_seconds() >= self._interval
               or self._hub_ok != self._sent_hub_ok
               or self._mode != self._sent_mode
               or self._mode_request != self._sent_mode_request)
        if not due:
            return False
        # What goes out now: a result set while this PUT is in flight (the cycle reporting a mode
        # switch during a background heartbeat) is not in it, so it must still count as unsent.
        hub_ok, mode, mode_request = self._hub_ok, self._mode, self._mode_request
        try:
            await dav.put_bytes(PATH, json.dumps(self.payload(now)).encode(), "application/json")
        except Exception as exc:  # a heartbeat problem must never stop uploads
            log.warning("heartbeat not sent: %s: %s", type(exc).__name__, exc)
            return False
        self._last_sent, self._sent_hub_ok, self._sent_mode = now, hub_ok, mode
        self._sent_mode_request = mode_request
        return True
