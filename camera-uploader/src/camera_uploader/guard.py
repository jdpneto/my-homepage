"""Make reolink-aio unable to change anything on the hub.

The uploader only ever reads (search, download). reolink-aio, however, will on
its own switch on the hub's HTTPS/RTMP ports when an HTTP login fails
(`Host._login_try_ports` -> `Baichuan.set_port_enabled`). That happened once
during testing. Every method that could change device state is therefore
replaced, at class level, with one that raises. Class level matters: it also
catches the library's internal calls, not just ours.
"""

from __future__ import annotations

import functools
import inspect
import logging
import re

from reolink_aio.api import Host
from reolink_aio.baichuan.baichuan import Baichuan

log = logging.getLogger(__name__)

BLOCKED = re.compile(
    r"^(set_|reboot|send_setting|update_firmware|upload_firmware|ptz_|play_quick_reply"
    r"|subscribe|unsubscribe|format)"
)
_MARK = "__camera_uploader_blocked__"


class HubWriteBlocked(RuntimeError):
    """A reolink-aio call would have changed hub state; refused."""


def _blocker(cls_name: str, name: str, original):
    message = f"blocked {cls_name}.{name}: camera-uploader is read-only towards the hub"

    if inspect.iscoroutinefunction(original):
        @functools.wraps(original)
        async def blocked(*_args, **_kwargs):
            log.error(message)
            raise HubWriteBlocked(message)
    else:
        @functools.wraps(original)
        def blocked(*_args, **_kwargs):
            log.error(message)
            raise HubWriteBlocked(message)

    setattr(blocked, _MARK, True)
    return blocked


def install_read_only_guard() -> None:
    """Idempotently replace every state-changing method on Host and Baichuan."""
    for cls in (Host, Baichuan):
        for name in dir(cls):
            if not BLOCKED.match(name):
                continue
            attr = getattr(cls, name)
            if not callable(attr) or getattr(attr, _MARK, False):
                continue
            setattr(cls, name, _blocker(cls.__name__, name, attr))
