"""Which time range to ask the hub about on each cycle."""

from __future__ import annotations

from datetime import datetime, timedelta

DEFAULT_OVERLAP = timedelta(minutes=10)


def compute_window(now: datetime, watermark: datetime | None, backfill_days: int,
                   overlap: timedelta = DEFAULT_OVERLAP) -> tuple[datetime, datetime]:
    """From just before the watermark (or backfill_days ago on first run) to now; never older than backfill_days."""
    floor = now - timedelta(days=backfill_days)
    if watermark is None:
        return floor, now
    return max(watermark - overlap, floor), now


def next_watermark(now: datetime, pending_starts: list[datetime], settle: timedelta) -> datetime:
    """Earliest start of a clip still to do; with none, everything up to now-settle is done."""
    ceiling = now - settle
    return min([*pending_starts, ceiling])
