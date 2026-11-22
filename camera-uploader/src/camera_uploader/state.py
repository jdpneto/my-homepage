"""Local record of which clips are on the VPS, which gave up, and how far we've caught up."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from typing import Literal

from .model import Camera

Status = Literal["new", "uploaded", "failed"]

_SCHEMA = """
CREATE TABLE IF NOT EXISTS clips (
    key         TEXT PRIMARY KEY,
    status      TEXT NOT NULL CHECK (status IN ('new', 'uploaded', 'failed')),
    remote_stem TEXT,
    attempts    INTEGER NOT NULL DEFAULT 0,
    last_error  TEXT,
    first_failed_at TEXT,
    updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS clips_remote_stem ON clips (remote_stem);
CREATE TABLE IF NOT EXISTS meta (
    name  TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


class StateStore:
    def __init__(self, path: Path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path, isolation_level=None)  # autocommit; explicit txns below
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA synchronous=FULL")
        self._db.executescript(_SCHEMA)

    def close(self) -> None:
        self._db.close()

    def status(self, key: str) -> Status:
        row = self._db.execute("SELECT status FROM clips WHERE key = ?", (key,)).fetchone()
        return row[0] if row else "new"

    def mark_uploaded(self, key: str, remote_stem: str) -> None:
        with self._txn():
            self._db.execute(
                "INSERT INTO clips (key, status, remote_stem) VALUES (?, 'uploaded', ?) "
                "ON CONFLICT(key) DO UPDATE SET status='uploaded', remote_stem=excluded.remote_stem, "
                "last_error=NULL, updated_at=datetime('now')",
                (key, remote_stem))

    def record_failure(self, key: str, error: str, max_attempts: int,
                       give_up_after: timedelta = timedelta(hours=6), now: datetime | None = None) -> bool:
        """Count a failed attempt; True when this attempt made the clip `failed`.

        A clip is given up only after `max_attempts` failures AND once its first
        failure is at least `give_up_after` old, so a burst of failures during a
        short outage never abandons clips.
        """
        now = now or datetime.now()
        with self._txn():
            self._db.execute(
                "INSERT INTO clips (key, status, attempts, last_error, first_failed_at) VALUES (?, 'new', 1, ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET attempts=attempts+1, last_error=excluded.last_error, "
                "first_failed_at=COALESCE(first_failed_at, excluded.first_failed_at), "
                "updated_at=datetime('now') WHERE status = 'new'",
                (key, error[:500], now.isoformat()))
            status, attempts, first = self._db.execute(
                "SELECT status, attempts, first_failed_at FROM clips WHERE key = ?", (key,)).fetchone()
            old_enough = first is not None and now - datetime.fromisoformat(first) >= give_up_after
            if status == "new" and attempts >= max_attempts and old_enough:
                self._db.execute("UPDATE clips SET status='failed' WHERE key = ?", (key,))
                return True
        return False

    def stem_taken_by_other(self, stem: str, key: str) -> bool:
        row = self._db.execute(
            "SELECT 1 FROM clips WHERE remote_stem = ? AND key != ? LIMIT 1", (stem, key)).fetchone()
        return row is not None

    def get_watermark(self) -> datetime | None:
        row = self._db.execute("SELECT value FROM meta WHERE name = 'watermark'").fetchone()
        return datetime.fromisoformat(row[0]) if row else None

    def set_watermark(self, dt: datetime) -> None:
        with self._txn():
            self._db.execute(
                "INSERT INTO meta (name, value) VALUES ('watermark', ?) "
                "ON CONFLICT(name) DO UPDATE SET value=excluded.value", (dt.isoformat(),))

    def get_camera_watermark(self, channel: int) -> datetime | None:
        """How far this camera (hub slot) has been caught up; None until its first full search."""
        value = self._get_meta(f"watermark.ch{channel}")
        return datetime.fromisoformat(value) if value else None

    def set_camera_watermark(self, channel: int, dt: datetime) -> None:
        with self._txn():
            self._set_meta(f"watermark.ch{channel}", dt.isoformat())

    def get_known_cameras(self) -> dict[int, Camera]:
        """Every hub slot ever seen with a camera UID: channel -> Camera (online unknown)."""
        value = self._get_meta("known_cameras")
        if not value:
            return {}
        return {int(ch): Camera(int(ch), str(cam["name"]), str(cam["uid"]))
                for ch, cam in json.loads(value).items()}

    def set_known_cameras(self, cameras: dict[int, Camera]) -> None:
        """Replace the remembered cameras (name and UID only: the online status is not kept)."""
        value = json.dumps({str(ch): {"name": cam.name, "uid": cam.uid} for ch, cam in sorted(cameras.items())},
                           ensure_ascii=False)
        with self._txn():
            self._set_meta("known_cameras", value)

    def get_applied_mode_request(self) -> str | None:
        """Id of the last mode request handled (applied, failed or expired): never handled twice."""
        return self._get_meta("mode_request_id")

    def get_mode_request_result(self) -> dict | None:
        """The heartbeat's `mode_request` for that request, kept across restarts."""
        value = self._get_meta("mode_request_result")
        return json.loads(value) if value else None

    def set_applied_mode_request(self, request_id: str, result: dict | None = None) -> None:
        """Remember `request_id` as handled (before the hub write), and later its result."""
        with self._txn():
            self._set_meta("mode_request_id", request_id)
            if result is None:
                self._db.execute("DELETE FROM meta WHERE name = 'mode_request_result'")
            else:
                self._set_meta("mode_request_result", json.dumps(result))

    def _get_meta(self, name: str) -> str | None:
        row = self._db.execute("SELECT value FROM meta WHERE name = ?", (name,)).fetchone()
        return row[0] if row else None

    def _set_meta(self, name: str, value: str) -> None:
        self._db.execute(
            "INSERT INTO meta (name, value) VALUES (?, ?) "
            "ON CONFLICT(name) DO UPDATE SET value=excluded.value", (name, value))

    def _txn(self):
        db = self._db

        class _Txn:
            def __enter__(self_inner):
                db.execute("BEGIN IMMEDIATE")

            def __exit__(self_inner, exc_type, *_):
                db.execute("ROLLBACK" if exc_type else "COMMIT")
                return False

        return _Txn()
