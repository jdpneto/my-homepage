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
