"""mode-request.json: a request from the VPS to switch the hub's mode (written only by the server)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime

PATH = "mode-request.json"
SWITCHABLE = frozenset({"disarm", "home", "away"})  # never "off"
MAX_TEXT = 4096  # a real request is ~200 bytes; anything bigger is refused unparsed


@dataclass(frozen=True)
class ModeRequest:
    id: str
    mode: str  # lower case; may be anything - only SWITCHABLE ones are ever applied
    requested_at: datetime  # aware
    expires_at: datetime  # aware


def _aware(value) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.strip())
    except ValueError:
        return None
    return dt if dt.tzinfo is not None and dt.utcoffset() is not None else None


def parse_request(text: str | None) -> ModeRequest | None:
    """The request in `text`, or None if there is none or it isn't a well-formed request. Never raises."""
    if not isinstance(text, str) or len(text) > MAX_TEXT or not text.strip():
        return None
    try:
        data = json.loads(text)
    except (ValueError, RecursionError):  # deep nesting raises RecursionError, not a ValueError
        return None
    if not isinstance(data, dict):
        return None
    rid, mode = data.get("id"), data.get("mode")
    if not isinstance(rid, str) or not rid.strip() or len(rid) > 100 or not isinstance(mode, str):
        return None
    requested, expires = _aware(data.get("requested_at")), _aware(data.get("expires_at"))
    if requested is None or expires is None:
        return None
    return ModeRequest(rid.strip(), mode.strip().lower(), requested, expires)
