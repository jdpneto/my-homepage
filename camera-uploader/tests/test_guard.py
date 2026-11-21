"""The uploader must never change hub settings (spec §6.3, §10.1).

reolink-aio switches on hub ports by itself during login fallbacks, so the guard
works at class level and covers the library's own internal calls.
"""

import inspect
from unittest.mock import MagicMock

import pytest
from reolink_aio.api import Host
from reolink_aio.baichuan.baichuan import Baichuan
from reolink_aio.baichuan.util import PortType

from camera_uploader.guard import BLOCKED, HubWriteBlocked, install_read_only_guard

MARK = "__camera_uploader_blocked__"


def _mutating_names(cls):
    return [n for n in dir(cls) if BLOCKED.match(n) and callable(getattr(cls, n))]


def test_every_mutating_method_is_blocked():
    install_read_only_guard()
    for cls in (Host, Baichuan):
        names = _mutating_names(cls)
        assert names, f"{cls.__name__} has no setters any more - review BLOCKED"
        unblocked = [n for n in names if not getattr(getattr(cls, n), MARK, False)]
        assert unblocked == [], f"{cls.__name__} methods left unblocked: {unblocked}"


def test_known_dangerous_methods_are_covered_by_pattern():
    # The methods that actually bit us or could brick/reconfigure the hub.
    for name in ("set_port_enabled", "set_net_port", "reboot", "send_setting",
                 "update_firmware", "upload_firmware", "set_recording", "set_push"):
        assert BLOCKED.match(name), name


async def test_baichuan_set_port_enabled_raises():
    install_read_only_guard()
    bc = Baichuan("192.0.2.1", "u", "p", http_api=MagicMock())
    with pytest.raises(HubWriteBlocked, match="Baichuan.set_port_enabled"):
        await bc.set_port_enabled(PortType.rtmp, True)


async def test_host_set_net_port_raises_without_network():
    install_read_only_guard()
    host = Host("192.0.2.1", "u", "p")
    try:
        with pytest.raises(HubWriteBlocked, match="Host.set_net_port"):
            await host.set_net_port(enable_https=True)
    finally:
        await host._aiohttp_session.close()


async def test_host_reboot_raises():
    install_read_only_guard()
    host = Host("192.0.2.1", "u", "p")
    try:
        with pytest.raises(HubWriteBlocked):
            await host.reboot()
    finally:
        await host._aiohttp_session.close()


def test_wrappers_keep_sync_or_async_nature():
    install_read_only_guard()
    for cls in (Host, Baichuan):
        for n in _mutating_names(cls):
            wrapper = getattr(cls, n)
            original = getattr(wrapper, "__wrapped__", None)
            assert original is not None, f"{cls.__name__}.{n} wrapper lost __wrapped__"
            assert inspect.iscoroutinefunction(wrapper) == inspect.iscoroutinefunction(original)


def test_install_is_idempotent():
    install_read_only_guard()
    first = Baichuan.set_port_enabled
    install_read_only_guard()
    assert Baichuan.set_port_enabled is first  # not wrapped twice


def test_read_methods_untouched():
    install_read_only_guard()
    for fn in (Baichuan.search_vod_type, Baichuan.download_mp4, Host.get_host_data,
               Host.login, Host.logout, Baichuan.get_ports):
        assert not getattr(fn, MARK, False), fn.__qualname__


# --- final-review fixes ---------------------------------------------------------

def test_ptz_read_getters_are_not_blocked_but_calibrate_is():
    # get_host_data calls ptz_pan_position/ptz_tilt_position for pan/tilt cameras.
    install_read_only_guard()
    for name in ("ptz_pan_position", "ptz_tilt_position", "ptz_presets", "ptz_patrols"):
        assert not getattr(getattr(Host, name), MARK, False), name
    assert getattr(Host.ptz_callibrate, MARK, False)


def test_unsubscribe_is_not_blocked():
    # expire_session() -> unsubscribe() runs on every connection error; blocking it masks the real error.
    install_read_only_guard()
    assert not getattr(Host.unsubscribe, MARK, False)
    assert getattr(Host.subscribe, MARK, False)


@pytest.mark.parametrize("cls,name", [
    (Baichuan, "UpgradeOnline"), (Baichuan, "AudioAlarmPlay"), (Baichuan, "SetRecV20"), (Baichuan, "SetPush"),
    (Baichuan, "PreAlarm"), (Baichuan, "QuickReplyPlay"), (Baichuan, "synchronize_time"), (Baichuan, "snapshot"),
    (Host, "sync_ntp"), (Host, "webhook_add"), (Host, "webhook_disable"),
])
def test_camelcase_and_other_writers_blocked(cls, name):
    install_read_only_guard()
    assert hasattr(cls, name), f"{cls.__name__}.{name} no longer exists - update the test"
    assert getattr(getattr(cls, name), MARK, False), f"{cls.__name__}.{name}"


@pytest.mark.parametrize("body,param", [
    ([{"cmd": "GetTime"}, {"cmd": "GetDevInfo", "action": 0}], None),
    ([{"cmd": "Login", "param": {}}], None),
    ([{"cmd": "Logout"}], None),
    ([{"cmd": "Search", "param": {}}], None),
    ([], {"cmd": "GetAbility"}),
])
def test_http_reads_allowed(body, param):
    from camera_uploader.guard import check_http_request
    check_http_request(body, param)  # no exception


@pytest.mark.parametrize("body,param", [
    ([{"cmd": "GetTime"}, {"cmd": "SetNetPort", "param": {}}], None),
    ([{"cmd": "Reboot"}], None),
    ([{"cmd": "Upgrade"}], None),
    ([{"cmd": "PtzCtrl"}], None),
    ([], {"cmd": "Snap"}),
    ([{"nocmd": 1}], None),
])
def test_http_writes_and_unknowns_blocked(body, param):
    from camera_uploader.guard import check_http_request
    with pytest.raises(HubWriteBlocked):
        check_http_request(body, param)


def test_http_send_chunk_is_wrapped():
    install_read_only_guard()
    assert getattr(Host.send_chunk, "__camera_uploader_http_checked__", False)


# --- remote mode switch: the single allowed hub write ----------------------------

def _scene_baichuan(scenes):
    from unittest.mock import AsyncMock

    bc = Baichuan("192.0.2.1", "u", "p", http_api=MagicMock())
    bc._scenes = dict(scenes)
    bc.send = AsyncMock(return_value="")  # the wire: nothing leaves the test
    bc.get_scene = AsyncMock(return_value=3)
    return bc


async def test_set_scene_still_blocked_and_switch_mode_is_the_only_way():
    from camera_uploader.guard import set_listed_scene

    install_read_only_guard()
    bc = _scene_baichuan({-1: "off", 1: "Disarm", 2: "Home", 3: "Away"})

    # set_scene itself stays blocked, by name, for us and for the library.
    assert getattr(Baichuan.set_scene, MARK, False)
    with pytest.raises(HubWriteBlocked, match="Baichuan.set_scene"):
        await bc.set_scene(3)
    with pytest.raises(HubWriteBlocked):
        await bc.set_scene(scene_name="Away")
    bc.send.assert_not_awaited()

    # The narrow door: only an id the hub listed, and never "off" (-1).
    for bad in (-1, 7, None, "3", True, 3.0):
        with pytest.raises(HubWriteBlocked):
            await set_listed_scene(bc, bad)
    bc.send.assert_not_awaited()

    await set_listed_scene(bc, 3)
    bc.send.assert_awaited_once()
    assert bc.send.await_args.kwargs["cmd_id"] == 602
    assert "<curSceneId>3</curSceneId>" in bc.send.await_args.kwargs["body"]

    # ... and the door did not open the guard: set_scene is still blocked afterwards.
    assert getattr(Baichuan.set_scene, MARK, False)
    with pytest.raises(HubWriteBlocked):
        await bc.set_scene(2)
    assert bc.send.await_count == 1
