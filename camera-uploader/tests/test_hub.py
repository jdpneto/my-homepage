from datetime import datetime
from types import SimpleNamespace

from reolink_aio.typings import VOD_trigger

from camera_uploader.hub import clips_from_search, count_untyped

T0 = datetime(2026, 11, 16, 19, 22, 58)
T1 = datetime(2026, 11, 16, 19, 23, 28)
T2 = datetime(2026, 11, 16, 19, 30, 0)
T3 = datetime(2026, 11, 16, 19, 30, 12)


def vod(start, end):
    return SimpleNamespace(start_time=start, end_time=end)


def empty():
    return {t: [] for t in VOD_trigger}


def test_merges_triggers_for_same_clip():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T0, T1)]
    by[VOD_trigger.PERSON] = [vod(T0, T1)]
    clips = clips_from_search(1, "Backyard", "uid1", by)
    assert len(clips) == 1
    c = clips[0]
    assert (c.channel, c.camera_name, c.camera_id, c.start, c.end) == (1, "Backyard", "uid1", T0, T1)
    assert c.triggers == frozenset({"motion", "person"})


def test_sorted_by_start_and_distinct_clips_kept():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T2, T3), vod(T0, T1)]
    clips = clips_from_search(0, "Garage", "uid0", by)
    assert [c.start for c in clips] == [T0, T2]


def test_same_start_different_end_are_two_clips():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T0, T1), vod(T0, T3)]
    assert len(clips_from_search(0, "Garage", "uid0", by)) == 2


def test_empty_search_returns_empty():
    assert clips_from_search(0, "Garage", "uid0", empty()) == []


def test_count_untyped_counts_only_none_entries():
    by_time = {"20261116192258": VOD_trigger.MOTION, "20261116193000": VOD_trigger.NONE,
               "20261116194000": VOD_trigger.NONE}
    assert count_untyped(by_time) == 2


# --- final-review fixes ---------------------------------------------------------
from datetime import timezone, timedelta as _td

import pytest
from reolink_aio.exceptions import CredentialsInvalidError, ReolinkConnectionError

from camera_uploader.hub import HubAuthError, HubError, ReolinkHub


def test_wrap_maps_bad_credentials_to_hub_auth_error():
    err = ReolinkHub._wrap("connect", CredentialsInvalidError("bad"))
    assert isinstance(err, HubAuthError) and isinstance(err, HubError)


def test_wrap_maps_connection_error_to_plain_hub_error():
    err = ReolinkHub._wrap("connect", ReolinkConnectionError("down"))
    assert type(err) is HubError


async def test_now_is_hub_local_naive_time(config_path):
    from camera_uploader.config import load_config

    hub = ReolinkHub(load_config(config_path))
    aware = datetime(2026, 11, 18, 20, 0, 5, tzinfo=timezone(_td(hours=-1)))
    hub._host = SimpleNamespace(time=lambda: aware)
    assert await hub.now() == datetime(2026, 11, 18, 20, 0, 5)


async def test_now_asks_hub_when_no_cached_time(config_path):
    from camera_uploader.config import load_config

    async def async_get_time():
        return datetime(2026, 11, 18, 20, 1, 0, tzinfo=timezone.utc)

    hub = ReolinkHub(load_config(config_path))
    hub._host = SimpleNamespace(time=lambda: None, async_get_time=async_get_time)
    assert await hub.now() == datetime(2026, 11, 18, 20, 1, 0)


def _hub_with_scene(config_path, get_scene, active="Away"):
    from camera_uploader.config import load_config
    from camera_uploader.hub import ReolinkHub

    hub = ReolinkHub(load_config(config_path))
    hub._host = SimpleNamespace(baichuan=SimpleNamespace(get_scene=get_scene, active_scene=active))
    return hub


async def test_mode_reads_active_scene_lower_case(config_path):
    async def get_scene():
        return 3

    assert await _hub_with_scene(config_path, get_scene, "Away").mode() == "away"


async def test_mode_failure_returns_none(config_path):
    async def broken():
        raise TimeoutError("hub slow")

    assert await _hub_with_scene(config_path, broken).mode() is None


async def test_mode_unknown_name_returns_none(config_path):
    from reolink_aio.const import UNKNOWN

    async def get_scene():
        return 7

    assert await _hub_with_scene(config_path, get_scene, UNKNOWN).mode() is None


def test_mode_is_not_blocked_by_guard():
    from reolink_aio.baichuan.baichuan import Baichuan

    from camera_uploader.guard import _MARK, install_read_only_guard

    install_read_only_guard()
    for name in ("get_scene", "get_scene_info"):
        assert not getattr(getattr(Baichuan, name), _MARK, False), name
    assert getattr(Baichuan.set_scene, _MARK, False)  # switching stays blocked


async def test_mode_name_missing_at_connect_is_read_on_demand(config_path):
    from reolink_aio.const import UNKNOWN

    from camera_uploader.config import load_config
    from camera_uploader.hub import ReolinkHub

    class Baichuan:  # the name load at connect failed (e.g. hub just rebooted)
        active_scene_id = 3
        names = {3: UNKNOWN}

        async def get_scene(self):
            return 3

        async def get_scene_info(self, scene_id):
            self.names[scene_id] = "Away"

        @property
        def active_scene(self):
            return self.names.get(self.active_scene_id, UNKNOWN)

    bc = Baichuan()
    hub = ReolinkHub(load_config(config_path))
    hub._host = SimpleNamespace(baichuan=bc)
    assert await hub.mode() == "away"


# --- remote mode switch ---------------------------------------------------------

def _switch_hub(config_path, scenes):
    from unittest.mock import AsyncMock, MagicMock

    from reolink_aio.baichuan.baichuan import Baichuan

    from camera_uploader.config import load_config
    from camera_uploader.guard import install_read_only_guard

    install_read_only_guard()
    bc = Baichuan("192.0.2.1", "u", "p", http_api=MagicMock())
    bc._scenes = dict(scenes)
    bc.send = AsyncMock(return_value="")
    bc.get_scene = AsyncMock(return_value=3)
    bc.get_scene_info = AsyncMock()
    hub = ReolinkHub(load_config(config_path))
    hub._host = SimpleNamespace(baichuan=bc)
    return hub, bc


async def test_switch_mode_calls_original_set_scene_with_listed_id(config_path):
    hub, bc = _switch_hub(config_path, {-1: "off", 1: "Disarm", 2: "Home", 3: "Away"})
    await hub.switch_mode("away")
    bc.send.assert_awaited_once()
    assert bc.send.await_args.kwargs["cmd_id"] == 602
    assert "<curSceneId>3</curSceneId>" in bc.send.await_args.kwargs["body"]
    await hub.switch_mode("Home")
    assert "<curSceneId>2</curSceneId>" in bc.send.await_args.kwargs["body"]


async def test_switch_mode_refuses_off_and_unlisted(config_path):
    from camera_uploader.hub import ModeSwitchError

    hub, bc = _switch_hub(config_path, {-1: "off", 1: "Disarm", 2: "Home", 9: "Away"})
    for name in ("off", "OFF", "party", "", "away; reboot"):
        with pytest.raises(ModeSwitchError, match="unknown mode"):
            await hub.switch_mode(name)
    # A negative id with a switchable name is still never selected.
    hub2, bc2 = _switch_hub(config_path, {-1: "Away", 2: "Home"})
    with pytest.raises(ModeSwitchError, match="unknown mode"):
        await hub2.switch_mode("away")
    # A switchable name the hub did not list.
    hub3, bc3 = _switch_hub(config_path, {-1: "off", 2: "Home"})
    with pytest.raises(ModeSwitchError, match="unknown mode"):
        await hub3.switch_mode("disarm")
    for b in (bc, bc2, bc3):
        b.send.assert_not_awaited()
    # Not connected.
    hub4 = ReolinkHub(hub._cfg)
    with pytest.raises(ModeSwitchError, match="hub not connected"):
        await hub4.switch_mode("away")


async def test_switch_mode_hub_error_is_a_short_reason(config_path):
    from camera_uploader.hub import ModeSwitchError

    hub, bc = _switch_hub(config_path, {1: "Disarm", 2: "Home", 3: "Away"})
    bc.send.side_effect = ReolinkConnectionError("socket closed")
    with pytest.raises(ModeSwitchError, match="hub refused"):
        await hub.switch_mode("away")


async def test_switch_mode_rechecks_expiry_before_the_write(config_path):
    from datetime import timedelta, timezone

    from camera_uploader.hub import ModeSwitchError

    hub, bc = _switch_hub(config_path, {1: "Disarm", 2: "Home", 3: "Away"})
    past = datetime.now(timezone.utc) - timedelta(seconds=1)
    with pytest.raises(ModeSwitchError, match="expired before pickup"):
        await hub.switch_mode("away", not_after=past)
    bc.send.assert_not_awaited()
    await hub.switch_mode("away", not_after=datetime.now(timezone.utc) + timedelta(minutes=1))
    bc.send.assert_awaited_once()
