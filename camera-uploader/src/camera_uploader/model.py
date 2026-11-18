"""The one domain object: a recorded clip on the hub."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


@dataclass(frozen=True)
class Clip:
    channel: int
    camera_name: str
    camera_id: str
    start: datetime  # naive, hub local time
    end: datetime  # naive, hub local time
    triggers: frozenset[str]

    @property
    def key(self) -> str:
        """Stable identity across restarts, camera renames and trigger reclassification."""
        return f"{self.camera_id}|{self.start:%Y%m%dT%H%M%S}|{self.end:%Y%m%dT%H%M%S}"
