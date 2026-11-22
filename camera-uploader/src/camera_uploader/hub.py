"""Read-only access to the Reolink Home Hub: list clips and download them."""

from __future__ import annotations

import asyncio
import logging
from dataclasses import replace
from datetime import datetime
from pathlib import Path

from reolink_aio.api import Host
from reolink_aio.const import UNKNOWN
from reolink_aio.exceptions import CredentialsInvalidError, LoginError, ReolinkConnectionError, ReolinkError
from reolink_aio.typings import VOD_trigger

from .config import Config
from .guard import HubWriteBlocked, install_read_only_guard, set_listed_scene
from .mode_request import SWITCHABLE
from .model import Camera, Clip

log = logging.getLogger(__name__)

SEARCH_TIMEOUT = 120
DOWNLOAD_TIMEOUT = 300
CONNECT_TIMEOUT = 60
MODE_TIMEOUT = 15
SWITCH_TIMEOUT = 30
STATUS_TIMEOUT = 30

# Read-only (a Get* HTTP command, let through by the guard). Lists every slot, online or not.
CHANNEL_STATUS = [{"cmd": "GetChannelstatus", "action": 0, "param": {}}]

# Failures of the link to the hub, not of one camera's search: the session is suspect, stop using it.
LINK_ERRORS = (asyncio.TimeoutError, OSError, ReolinkConnectionError, CredentialsInvalidError, LoginError,
               HubWriteBlocked)

# reolink-aio's search gives up after 50 pages with only this warning, then returns what it has.
_SEARCH_LOGGER = "reolink_aio.baichuan.baichuan"
_SEARCH_CAP_TEXT = "search_vod_type took more"


class HubError(Exception):
    """The hub could not be reached, refused, or misbehaved."""


class CameraSearchError(HubError):
    """One camera's search failed but the hub answered: the other cameras can still be searched."""


class SearchTruncated(CameraSearchError):
    """reolink-aio stopped after 50 pages and returned part of the list: search shorter spans."""


class HubAuthError(HubError):
    """The hub rejected the username/password. Retry slowly: the hub locks out repeated failures."""


class ModeSwitchError(Exception):
    """A mode switch was not done; the message is a short reason for the page ("hub refused", ...)."""


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


def merge_channel_status(known: dict[int, Camera], response) -> dict[int, Camera]:
    """`known` updated from a GetChannelstatus answer; raises ValueError when the answer is unusable.

    A slot reporting a UID is learned (online or not); a slot reporting no name or UID keeps the
    remembered ones (battery cameras that are asleep or offline often report neither). Known
    cameras get the slot's online flag; a slot never seen with a UID is not a camera.
    """
    try:
        (data,) = [d for d in response if isinstance(d, dict) and d.get("cmd") == "GetChannelstatus"]
        if data.get("code") != 0:
            raise ValueError(f"hub answered code {data.get('code')!r}: {data.get('error')!r}")
        slots = data["value"]["status"]
        if not isinstance(slots, list):
            raise TypeError("status is not a list")
    except (TypeError, KeyError, ValueError) as exc:
        raise ValueError(f"unusable GetChannelstatus answer: {type(exc).__name__}: {exc}") from exc
    merged = {ch: replace(cam, online=None) for ch, cam in known.items()}
    for info in slots:
        if not isinstance(info, dict) or type(info.get("channel")) is not int:
            continue
        ch = info["channel"]
        uid = info.get("uid") if isinstance(info.get("uid"), str) else ""
        name = info.get("name") if isinstance(info.get("name"), str) else ""
        name = "" if name.strip() in ("", "0", "1") else name.strip()  # some devices answer "0"/"1"
        old = merged.get(ch)
        if old is None and not uid:
            continue
        merged[ch] = Camera(ch, name or (old.name if old else "") or f"Camera {ch + 1}",
                            uid or old.uid, online=info.get("online") == 1)
    return merged


def count_untyped(vod_by_time: dict) -> int:
    """Recordings the hub reported with no trigger reolink-aio understands.

    reolink-aio's Baichuan search returns file objects only for known trigger
    types; these exist only as a count, so they cannot be downloaded.
    """
    return sum(1 for t in vod_by_time.values() if t == VOD_trigger.NONE)


def _library_uid(value) -> str:
    """A UID as reolink-aio compares it (it drops a "_..." suffix); "" when unknown."""
    if not isinstance(value, str) or value in ("", UNKNOWN):
        return ""
    return value.split("_")[0]


class _SearchCapWatch(logging.Handler):
    """Notices reolink-aio's "took more then 50 itterations" warning during one search."""

    def __init__(self):
        super().__init__(logging.WARNING)
        self.hit = False

    def emit(self, record: logging.LogRecord) -> None:
        if _SEARCH_CAP_TEXT in str(record.msg):
            self.hit = True


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
            if name == UNKNOWN and getattr(bc, "active_scene_id", -1) >= 0:
                # The name was not loaded at connect (e.g. the hub had just rebooted): read it now.
                await asyncio.wait_for(bc.get_scene_info(bc.active_scene_id), MODE_TIMEOUT)
                name = bc.active_scene
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"  # a TimeoutError has an empty message
            if self._mode_error != error:
                log.warning("could not read the hub's mode: %s", error)
            self._mode_error = error
            return None
        self._mode_error = None
        if not isinstance(name, str) or not name or name == UNKNOWN:
            return None
        return name.lower()

    async def switch_mode(self, name: str, not_after: datetime | None = None) -> None:
        """Make the hub's mode `name` ("disarm", "home" or "away") active - the uploader's only hub write.

        Goes through guard.set_listed_scene: only a scene the hub listed with an id >= 0.
        Raises ModeSwitchError with a short reason; nothing is written when the mode is unknown,
        or when this Mac's clock has reached `not_after` (aware) by the time of the write.
        """
        wanted = name.strip().lower() if isinstance(name, str) else ""
        if wanted not in SWITCHABLE:
            raise ModeSwitchError("unknown mode")
        if self._host is None:
            raise ModeSwitchError("hub not connected")
        bc = self._host.baichuan
        scene_id = self._listed_scene_id(bc, wanted)
        if scene_id is None and any(n == UNKNOWN for i, n in self._scenes(bc).items() if i >= 0):
            await self._load_mode_names(self._host)  # names not loaded at connect: try once more
            scene_id = self._listed_scene_id(bc, wanted)
        if scene_id is None:
            raise ModeSwitchError("unknown mode")
        # Re-checked right before the write: reloading the names above can take a while.
        if not_after is not None and datetime.now().astimezone() >= not_after:
            raise ModeSwitchError("expired before pickup")
        try:
            await asyncio.wait_for(set_listed_scene(bc, scene_id), SWITCH_TIMEOUT)
        except HubWriteBlocked as exc:
            raise ModeSwitchError("unknown mode") from exc
        except asyncio.TimeoutError as exc:
            log.warning("switching the hub to %s timed out", wanted)
            raise ModeSwitchError("hub timed out") from exc
        except Exception as exc:
            log.warning("the hub refused to switch to %s: %s: %s", wanted, type(exc).__name__, exc)
            raise ModeSwitchError("hub refused") from exc
        log.info("switched the hub to %s (scene %s)", wanted, scene_id)

    @staticmethod
    def _scenes(bc) -> dict:
        scenes = getattr(bc, "_scenes", None)
        return scenes if isinstance(scenes, dict) else {}

    @classmethod
    def _listed_scene_id(cls, bc, wanted: str) -> int | None:
        """The id >= 0 of the one scene the hub lists under `wanted`; None if none (or ambiguous)."""
        ids = [i for i, n in cls._scenes(bc).items()
               if type(i) is int and i >= 0 and isinstance(n, str) and n.strip().lower() == wanted]
        return ids[0] if len(ids) == 1 else None

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

    async def info(self) -> dict:
        host = self._require()
        return {"model": host.model, "firmware": host.sw_version}

    async def refresh_cameras(self, known: dict[int, Camera]) -> dict[int, Camera]:
        """`known` updated from the hub's slot list (read-only GetChannelstatus). Never raises:
        on any failure the remembered cameras are returned as they are (online unknown).

        reolink-aio's own channel list is filled only at login and only with the slots online
        then; battery cameras come and go, so the uploader keeps its own list instead.
        """
        base = self._with_login_cameras(known)
        try:
            response = await asyncio.wait_for(
                self._require().send(CHANNEL_STATUS, expected_response_type="json"), STATUS_TIMEOUT)
            merged = merge_channel_status(base, response)
        except (asyncio.CancelledError, KeyboardInterrupt, SystemExit):
            raise
        except BaseException as exc:
            log.warning("could not read the hub's camera list, using the remembered one: %s: %s",
                        type(exc).__name__, exc)
            merged = base
        for ch, cam in sorted(merged.items()):
            old = known.get(ch)
            if old is None:
                log.info("learned camera %r on channel %s (uid %s)", cam.name, ch, cam.uid)
            elif (old.name, old.uid) != (cam.name, cam.uid):
                log.info("camera on channel %s is now %r (uid %s), was %r (uid %s)",
                         ch, cam.name, cam.uid, old.name, old.uid)
        return merged

    def _with_login_cameras(self, known: dict[int, Camera]) -> dict[int, Camera]:
        """`known` plus the slots reolink-aio learned with a UID at login (remembered values win)."""
        host = self._host
        cameras = dict(known)
        if host is None:
            return cameras
        try:
            for ch in host.channels:
                uid = host.camera_uid(ch)
                if ch in cameras or type(ch) is not int or not isinstance(uid, str) or uid in ("", UNKNOWN):
                    continue
                name = host.camera_name(ch)
                name = name.strip() if isinstance(name, str) and name not in ("", UNKNOWN) else ""
                cameras[ch] = Camera(ch, name or f"Camera {ch + 1}", uid)
        except Exception as exc:  # login data is a bonus; never fail the refresh over it
            log.warning("could not read the cameras reolink-aio learned at login: %s: %s", type(exc).__name__, exc)
            return dict(known)
        return cameras

    @staticmethod
    def _teach(host: Host, channel: int, name: str, uid: str) -> None:
        """reolink-aio refuses to search or download a slot whose UID it doesn't know ("cannot get
        UID for channel"), and learns UIDs only at login, for the slots online then. The uploader's
        list (refreshed every cycle) is the authority: a missing or stale UID or name is replaced."""
        uids, names = host._uid, host._name
        if uid and _library_uid(uids.get(channel)) != _library_uid(uid):
            uids[channel] = uid
        if name and names.get(channel) != name:
            names[channel] = name

    async def list_clips(self, camera: Camera, start: datetime, end: datetime) -> list[Clip]:
        """This camera's clips that start in [start, end].

        Raises CameraSearchError when the hub answered but this camera's search failed (other
        cameras can still be searched), SearchTruncated when reolink-aio stopped after 50 pages
        (search shorter spans), and a plain HubError when the link to the hub failed.
        """
        host = self._require()
        ch = camera.channel
        what = f"search channel {ch} ({camera.name})"
        self._teach(host, ch, camera.name, camera.uid)
        watch = _SearchCapWatch()
        library_log = logging.getLogger(_SEARCH_LOGGER)
        library_log.addHandler(watch)
        try:
            by_time, by_trigger = await asyncio.wait_for(
                host.baichuan.search_vod_type(ch, start, end, stream="main"), SEARCH_TIMEOUT)
        except BaseException as exc:
            if isinstance(exc, ReolinkError) and not isinstance(exc, LINK_ERRORS):
                raise CameraSearchError(f"{what}: {type(exc).__name__}: {exc}") from exc
            raise self._wrap(what, exc) from exc
        finally:
            library_log.removeHandler(watch)
        if watch.hit:
            raise SearchTruncated(f"{what}: reolink-aio stopped after 50 pages for {start} - {end}")
        untyped = count_untyped(by_time)
        if untyped:
            log.warning("channel %s: %d recording(s) with an unknown trigger type cannot be listed "
                        "by reolink-aio and are skipped", ch, untyped)
        clips = clips_from_search(ch, camera.name, camera.uid, by_trigger)
        return sorted(clips, key=lambda c: (c.start, c.channel))

    async def download(self, clip: Clip, dest: Path) -> int:
        host = self._require()
        self._teach(host, clip.channel, clip.camera_name, clip.camera_id)
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
