"""A stand-in for reolink-aio's Host/Baichuan, shaped like the real Home Hub Mini (probe of 22 Nov 2026).

Like the real library, it refuses to search or download a slot whose UID it does not know.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace

from reolink_aio.const import UNKNOWN
from reolink_aio.exceptions import InvalidParameterError
from reolink_aio.typings import VOD_trigger

from camera_uploader.guard import check_http_request

GARAGE, BACKYARD, LIVING, FRONT = "9527000O0NWN1LLO", "9527000OBACKYARD", "9527000OMIMEWIC0", "9527000OMIMV1XVE"


def slot(channel, online=0, name="", uid="", sleep=0):
    return {"channel": channel, "online": online, "sleep": sleep, "name": name, "uid": uid}


# GetChannelstatus as the hub answered it: slot 1 (Backyard) offline with no name/uid, 4-7 empty.
PROBE_STATUS = [
    slot(0, 1, "Garage", GARAGE, 1),
    slot(1),
    slot(2, 1, "Living Room", LIVING, 1),
    slot(3, 1, "Front door", FRONT, 1),
    *[slot(n) for n in range(4, 8)],
]


def channel_status_response(status):
    return [{"cmd": "GetChannelstatus", "code": 0, "value": {"count": len(status), "status": status}}]


class FakeBaichuan:
    def __init__(self, host, recordings, search_error=None, truncate=()):
        self.host, self.recordings = host, recordings  # channel -> [(start, end)]
        self.search_error = search_error  # raised by every search (after the UID check)
        self.truncate = set(truncate)  # channels whose search hits the library's 50-page cap
        self.searches: list[tuple] = []  # (channel, uid known then, name known then)
        self.downloads: list[tuple] = []

    def _uid_or_refuse(self, what, channel):
        uid = self.host.camera_uid(channel)
        if uid == UNKNOWN:
            raise InvalidParameterError(f"Baichuan host 192.0.2.1: {what}: cannot get UID for channel {channel}")
        return uid

    async def search_vod_type(self, channel, start, end, stream=None, split_time=None):
        self.searches.append((channel, self.host._uid.get(channel), self.host._name.get(channel)))
        self._uid_or_refuse("search_vod_type", channel)
        if self.search_error is not None:
            raise self.search_error
        files = [SimpleNamespace(start_time=s, end_time=e)
                 for s, e in self.recordings.get(channel, []) if start <= s <= end]
        if channel in self.truncate:  # what reolink-aio does: a library warning, then partial results
            logging.getLogger("reolink_aio.baichuan.baichuan").warning(
                "Baichuan host 192.0.2.1: search_vod_type took more then 50 itterations, quitting")
            files = files[:1]
        by_trigger = {t: [] for t in VOD_trigger}
        by_trigger[VOD_trigger.MOTION] = files
        return {f"{f.start_time:%Y%m%d%H%M%S}": VOD_trigger.MOTION for f in files}, by_trigger

    async def download_mp4(self, channel, start, end, stream="sub", ffmpeg="ffmpeg"):
        self.downloads.append((channel, self.host._uid.get(channel), start))
        self._uid_or_refuse("download recording", channel)
        return f"mp4 ch{channel} {start:%H%M%S}".encode()

    async def get_scene(self):
        raise TimeoutError("not under test")


class FakeHost:
    model = "Reolink Home Hub Mini"
    sw_version = "v3.3.0.609_26092101"

    def __init__(self, channels=(0, 2, 3), status=None, recordings=None, send_error=None, uids=None, names=None,
                 search_error=None, truncate=()):
        # What reolink-aio learned at login: only the slots that were online then.
        status = PROBE_STATUS if status is None else status
        self.channels = list(channels)
        by_ch = {s["channel"]: s for s in status}
        self._uid = dict(uids) if uids is not None else {ch: by_ch[ch]["uid"] for ch in self.channels}
        self._name = dict(names) if names is not None else {ch: by_ch[ch]["name"] for ch in self.channels}
        self.status, self.send_error = status, send_error
        self.sent: list[tuple] = []
        self.baichuan = FakeBaichuan(self, recordings or {}, search_error, truncate)

    async def send(self, body, param=None, expected_response_type="json", retry=3):
        check_http_request(body, param)  # the read-only guard's transport check, as on the real Host
        self.sent.append((body, expected_response_type))
        if self.send_error is not None:
            raise self.send_error
        return channel_status_response(self.status)

    def camera_uid(self, channel):
        return self._uid.get(channel, UNKNOWN)

    def camera_name(self, channel):
        return self._name.get(channel, UNKNOWN)

    def camera_model(self, channel):
        return UNKNOWN

    def time(self):
        return None
