"""Read-only access to the Reolink Home Hub: list clips and download them."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime
from pathlib import Path

from reolink_aio.api import Host
from reolink_aio.const import UNKNOWN
from reolink_aio.exceptions import CredentialsInvalidError, ReolinkError
from reolink_aio.typings import VOD_trigger

from .config import Config
from .guard import HubWriteBlocked, install_read_only_guard
from .model import Clip

log = logging.getLogger(__name__)

SEARCH_TIMEOUT = 120
DOWNLOAD_TIMEOUT = 300
CONNECT_TIMEOUT = 60
MODE_TIMEOUT = 15


class HubError(Exception):
    """The hub could not be reached, refused, or misbehaved."""


class HubAuthError(HubError):
    """The hub rejected the username/password. Retry slowly: the hub locks out repeated failures."""


def clips_from_search(channel: int, camera_name: str, camera_id: str, vod_by_trigger: dict) -> list[Clip]:
    """Merge reolink-aio's per-trigger lists into one Clip per (start, end)."""
    merged: dict[tuple[datetime, datetime], set[str]] = {}
    for trigger, files in vod_by_trigger.items():
        for f in files:
            merged.setdefault((f.start_time, f.end_time), set()).add(trigger.name.lower())
    return [
        Clip(channel, camera_name, camera_id, start, end, frozenset(triggers))
        for (start, end), triggers in sorted(merged.items())
    ]


def count_untyped(vod_by_time: dict) -> int:
    """Recordings the hub reported with no trigger reolink-aio understands.

    reolink-aio's Baichuan search returns file objects only for known trigger
    types; these exist only as a count, so they cannot be downloaded.
    """
    return sum(1 for t in vod_by_time.values() if t == VOD_trigger.NONE)


class ReolinkHub:
    def __init__(self, cfg: Config):
        self._cfg = cfg
        self._host: Host | None = None
        self._mode_error: str | None = None

    async def connect(self) -> None:
        install_read_only_guard()
        host = Host(self._cfg.hub_host, self._cfg.hub_user, self._cfg.hub_password)
        try:
            await asyncio.wait_for(host.get_host_data(), CONNECT_TIMEOUT)
        except BaseException as exc:
            await self._safe_logout(host)
            raise self._wrap("connect", exc) from exc
        self._host = host
        log.info("connected to hub %s (%s, firmware %s), channels %s",
                 self._cfg.hub_host, host.model, host.sw_version, host.channels)
        await self._load_mode_names(host)

    async def _load_mode_names(self, host) -> None:
        """Names of the hub's modes (scenes), read once per connection; best effort, read-only."""
        bc = getattr(host, "baichuan", None)
        for scene_id in sorted(getattr(bc, "_scenes", {}) or {}):
            if scene_id < 0:
                continue
            try:
                await asyncio.wait_for(bc.get_scene_info(scene_id), MODE_TIMEOUT)
            except Exception as exc:  # names are only for display; never fail the connection
                log.warning("could not read the name of hub mode %s: %s", scene_id, exc)

    async def mode(self) -> str | None:
        """The hub's active mode in lower case ("away", "home", "disarm", "off"), or None if unknown.
        Read-only (Baichuan cmd 601) and never raises: a failure must not affect uploads."""
        try:
            bc = self._require().baichuan
            await asyncio.wait_for(bc.get_scene(), MODE_TIMEOUT)
            name = bc.active_scene
        except Exception as exc:
            if self._mode_error != str(exc):
                log.warning("could not read the hub's mode: %s", exc)
            self._mode_error = str(exc)
            return None
        self._mode_error = None
        if not isinstance(name, str) or not name or name == UNKNOWN:
            return None
        return name.lower()

    async def now(self) -> datetime:
        """The hub's own local wall-clock time (naive), the clock its clip timestamps use."""
        host = self._require()
        current = host.time()
        if current is None:
            try:
                current = await asyncio.wait_for(host.async_get_time(), SEARCH_TIMEOUT)
            except BaseException as exc:
                raise self._wrap("get hub time", exc) from exc
        return current.replace(tzinfo=None)

    @property
    def camera_count(self) -> int | None:
        return len(self._host.channels) if self._host is not None else None

    async def info(self) -> dict:
        host = self._require()
        return {"model": host.model, "firmware": host.sw_version}

    def _camera_id(self, ch: int) -> str:
        host = self._require()
        uid = host.camera_uid(ch)
        if uid and uid != UNKNOWN:
            return uid
        return f"ch{ch}-{host.camera_model(ch)}"

    async def list_clips(self, start: datetime, end: datetime) -> list[Clip]:
        host = self._require()
        clips: list[Clip] = []
        for ch in host.channels:
            try:
                by_time, by_trigger = await asyncio.wait_for(
                    host.baichuan.search_vod_type(ch, start, end, stream="main"), SEARCH_TIMEOUT)
            except BaseException as exc:
                raise self._wrap(f"search channel {ch}", exc) from exc
            untyped = count_untyped(by_time)
            if untyped:
                log.warning("channel %s: %d recording(s) with an unknown trigger type cannot be listed "
                            "by reolink-aio and are skipped", ch, untyped)
            clips.extend(clips_from_search(ch, host.camera_name(ch), self._camera_id(ch), by_trigger))
        return sorted(clips, key=lambda c: (c.start, c.channel))

    async def download(self, clip: Clip, dest: Path) -> int:
        host = self._require()
        try:
            data = await asyncio.wait_for(
                host.baichuan.download_mp4(clip.channel, clip.start, clip.end, stream="main",
                                           ffmpeg=self._cfg.ffmpeg),
                DOWNLOAD_TIMEOUT)
        except BaseException as exc:
            raise self._wrap(f"download {clip.key}", exc) from exc
        dest.write_bytes(data)
        return len(data)

    async def close(self) -> None:
        host, self._host = self._host, None
        if host is not None:
            await self._safe_logout(host)

    def _require(self) -> Host:
        if self._host is None:
            raise HubError("not connected")
        return self._host

    @staticmethod
    def _wrap(what: str, exc: BaseException) -> BaseException:
        if isinstance(exc, (asyncio.CancelledError, KeyboardInterrupt, SystemExit)):
            return exc
        if isinstance(exc, CredentialsInvalidError):
            return HubAuthError(f"{what}: hub rejected the username/password ({exc})")
        if isinstance(exc, (ReolinkError, HubWriteBlocked, OSError, asyncio.TimeoutError)):
            return HubError(f"{what}: {type(exc).__name__}: {exc}")
        return HubError(f"{what}: unexpected {type(exc).__name__}: {exc}")

    @staticmethod
    async def _safe_logout(host: Host) -> None:
        try:
            await asyncio.wait_for(host.logout(), 15)
        except Exception as exc:  # logout is best effort
            log.debug("hub logout failed: %s", exc)
        session = getattr(host, "_aiohttp_session", None)
        if session is not None and not session.closed:
            await session.close()
