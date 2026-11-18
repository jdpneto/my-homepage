"""Make reolink-aio unable to change anything on the hub.

The uploader only ever reads (search, download). reolink-aio, however, will on
its own switch on the hub's HTTPS/RTMP ports when an HTTP login fails
(`Host._login_try_ports` -> `Baichuan.set_port_enabled`). That happened once
during testing. Two layers stop this:

1. Name layer: every state-changing method on Host and Baichuan is replaced, at
   class level, with one that raises. Class level matters: it also catches the
   library's internal calls, not just ours.
2. HTTP transport layer: Host.send_chunk only lets through JSON commands that
   read (Get*, Search, Login, Logout); anything else raises.

Read-only helpers whose names merely look like writers (ptz_pan_position,
unsubscribe, ...) are deliberately left alone: get_host_data and the library's
error clean-up call them.
"""

from __future__ import annotations

import functools
import inspect
import logging
import re
from typing import Any

from reolink_aio.api import Host
from reolink_aio.baichuan.baichuan import Baichuan

log = logging.getLogger(__name__)

BLOCKED = re.compile(
    r"^(set_|Set[A-Z]|reboot|send_setting|update_firmware|upload_firmware|ptz_callibrate|play_quick_reply"
    r"|subscribe|UpgradeOnline|AudioAlarmPlay|PreAlarm|QuickReplyPlay|StartZoomFocus|synchronize_time"
    r"|snapshot|sync_ntp|webhook_|format)"
)
HTTP_ALLOWED = re.compile(r"^(Get[A-Z]\w*|Search|Login|Logout)$")

_MARK = "__camera_uploader_blocked__"
_HTTP_MARK = "__camera_uploader_http_checked__"


class HubWriteBlocked(RuntimeError):
    """A reolink-aio call would have changed hub state; refused."""


def _refuse(message: str):
    log.error(message)
    raise HubWriteBlocked(message)


def _blocker(cls_name: str, name: str, original):
    message = f"blocked {cls_name}.{name}: camera-uploader is read-only towards the hub"

    if inspect.iscoroutinefunction(original):
        @functools.wraps(original)
        async def blocked(*_args, **_kwargs):
            _refuse(message)
    else:
        @functools.wraps(original)
        def blocked(*_args, **_kwargs):
            _refuse(message)

    setattr(blocked, _MARK, True)
    return blocked


def check_http_request(body: Any, param: Any) -> None:
    """Raise unless every command in an HTTP API request is a read."""
    cmds = []
    if isinstance(param, dict) and "cmd" in param:
        cmds.append(param["cmd"])
    for item in body or []:
        cmds.append(item.get("cmd") if isinstance(item, dict) else None)
    for cmd in cmds:
        if not isinstance(cmd, str) or not HTTP_ALLOWED.match(cmd):
            _refuse(f"blocked HTTP command {cmd!r}: camera-uploader is read-only towards the hub")


def _wrap_send_chunk() -> None:
    original = Host.send_chunk
    if getattr(original, _HTTP_MARK, False):
        return

    @functools.wraps(original)
    async def send_chunk(self, body, param=None, *args, **kwargs):
        check_http_request(body, param)
        return await original(self, body, param, *args, **kwargs)

    setattr(send_chunk, _HTTP_MARK, True)
    Host.send_chunk = send_chunk


def install_read_only_guard() -> None:
    """Idempotently install both layers on Host and Baichuan."""
    for cls in (Host, Baichuan):
        for name in dir(cls):
            if not BLOCKED.match(name):
                continue
            attr = getattr(cls, name)
            if not callable(attr) or getattr(attr, _MARK, False):
                continue
            setattr(cls, name, _blocker(cls.__name__, name, attr))
    _wrap_send_chunk()
