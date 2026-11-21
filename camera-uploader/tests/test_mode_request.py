import json
from datetime import datetime, timedelta, timezone

from camera_uploader.mode_request import ModeRequest, parse_request

UTC = timezone.utc


def _text(**over):
    data = {"id": "6f1c2a8e-0000-4000-8000-000000000001", "mode": "away",
            "requested_at": "2026-11-21T10:00:00Z", "expires_at": "2026-11-21T10:05:00Z"}
    data.update(over)
    return json.dumps(data)


def test_parse_valid():
    req = parse_request(_text())
    assert req == ModeRequest(id="6f1c2a8e-0000-4000-8000-000000000001", mode="away",
                              requested_at=datetime(2026, 11, 21, 10, 0, tzinfo=UTC),
                              expires_at=datetime(2026, 11, 21, 10, 5, tzinfo=UTC))
    # Java's OffsetDateTime/Instant strings (offsets, nanoseconds) and mode case.
    req = parse_request(_text(mode="Home", requested_at="2026-11-21T11:00:00.123456789+01:00",
                              expires_at="2026-11-21T11:05:00.123456789+01:00"))
    assert req.mode == "home"
    assert req.expires_at - req.requested_at == timedelta(minutes=5)
    assert req.expires_at.utcoffset() == timedelta(hours=1)
    # An unknown mode still parses: the cycle reports it as failed.
    assert parse_request(_text(mode="off")).mode == "off"


def test_parse_garbage_none():
    for text in (None, "", "   ", "not json", "[]", "null", "42",
                 "camera clips live on the 15 GB volume - do not delete\n",
                 _text(id=""), _text(id=None), _text(id=7), _text(mode=None), _text(mode=3),
                 _text(requested_at="yesterday"), _text(expires_at=None),
                 _text(expires_at="2026-11-21T10:05:00"),  # no offset: ambiguous
                 json.dumps({"id": "x", "mode": "away"})):
        assert parse_request(text) is None, text


def test_parse_hostile_none():
    # Deep nesting makes json.loads raise RecursionError (not a ValueError); a huge file is refused unread.
    for text in ("[" * 200_000, "{\"a\":" * 200_000, _text(pad="x" * 10_000), " " * 10_000 + _text()):
        assert parse_request(text) is None
