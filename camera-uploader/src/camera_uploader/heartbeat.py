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
        self._last_sent: datetime | None = None
        self._sent_hub_ok: bool | None = None
        self._last_ok: datetime | None = None
        self._skew: float | None = None
        self._suppressed = False

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
            "last_error": self._last_error,
            "last_ok": self._last_ok.isoformat(timespec="seconds") if self._last_ok else None,
            "hub_clock_skew_seconds": round(self._skew) if self._skew is not None else None,
        }

    async def maybe_send(self, dav, now: datetime) -> bool:
        if self._suppressed:
            return False
        due = (self._last_sent is None
               or (now - self._last_sent).total_seconds() >= self._interval
               or self._hub_ok != self._sent_hub_ok)
        if not due:
            return False
        try:
            await dav.put_bytes(PATH, json.dumps(self.payload(now)).encode(), "application/json")
        except Exception as exc:  # a heartbeat problem must never stop uploads
            log.warning("heartbeat not sent: %s: %s", type(exc).__name__, exc)
            return False
        self._last_sent, self._sent_hub_ok = now, self._hub_ok
        return True
