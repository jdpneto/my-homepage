import json
import re
from datetime import datetime
from zoneinfo import ZoneInfo

from camera_uploader.model import Clip
from camera_uploader.sidecar import build_sidecar

HUB = {"model": "Reolink Home Hub Mini", "firmware": "v3.3.0.609_26092101"}


def clip(start, end):
    return Clip(1, "Backyard", "9527000O0NW91S80", start, end, frozenset({"person", "motion"}))


def test_sidecar_fields_winter():
    s = build_sidecar(clip(datetime(2026, 11, 16, 19, 22, 58), datetime(2026, 11, 16, 19, 23, 28)),
                      HUB, "0.1.0", ZoneInfo("Europe/Lisbon"))
    assert s == {
        "camera_name": "Backyard",
        "camera_id": "9527000O0NW91S80",
        "channel": 1,
        "start": "2026-11-16T19:22:58+00:00",
        "end": "2026-11-16T19:23:28+00:00",
        "duration_seconds": 30,
        "triggers": ["motion", "person"],
        "hub_model": "Reolink Home Hub Mini",
        "hub_firmware": "v3.3.0.609_26092101",
        "uploader_version": "0.1.0",
        "mode": None,
    }
    json.dumps(s)  # serialisable


def test_sidecar_summer_offset():
    s = build_sidecar(clip(datetime(2026, 7, 1, 12, 0, 0), datetime(2026, 7, 1, 12, 0, 10)),
                      HUB, "0.1.0", ZoneInfo("Europe/Lisbon"))
    assert s["start"] == "2026-07-01T12:00:00+01:00"


def test_sidecar_default_tz_is_local_with_offset():
    s = build_sidecar(clip(datetime(2026, 11, 16, 19, 22, 58), datetime(2026, 11, 16, 19, 23, 28)),
                      HUB, "0.1.0", None)
    assert re.fullmatch(r"2026-11-16T19:22:58[+-]\d\d:\d\d", s["start"])


def test_sidecar_has_mode():
    s = build_sidecar(clip(datetime(2026, 11, 16, 19, 22, 58), datetime(2026, 11, 16, 19, 23, 28)),
                      HUB, "0.1.0", ZoneInfo("Europe/Lisbon"), mode="away")
    assert s["mode"] == "away"
