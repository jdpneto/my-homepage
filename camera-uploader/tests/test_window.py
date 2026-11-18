from datetime import datetime, timedelta

from camera_uploader.window import compute_window, next_watermark

NOW = datetime(2026, 11, 18, 21, 0, 0)


def test_first_run_window_is_backfill_days():
    assert compute_window(NOW, None, 30) == (NOW - timedelta(days=30), NOW)


def test_window_starts_overlap_before_watermark():
    wm = NOW - timedelta(minutes=2)
    assert compute_window(NOW, wm, 30) == (wm - timedelta(minutes=10), NOW)


def test_catch_up_capped_at_backfill_days_after_long_outage():
    wm = NOW - timedelta(days=40)
    assert compute_window(NOW, wm, 30) == (NOW - timedelta(days=30), NOW)


def test_custom_overlap():
    wm = NOW - timedelta(hours=1)
    start, _ = compute_window(NOW, wm, 30, overlap=timedelta(minutes=1))
    assert start == wm - timedelta(minutes=1)


def test_next_watermark_is_earliest_pending_start():
    a, b = NOW - timedelta(minutes=5), NOW - timedelta(minutes=1)
    assert next_watermark(NOW, [b, a], timedelta(seconds=20)) == a


def test_next_watermark_without_pending_is_now_minus_settle():
    assert next_watermark(NOW, [], timedelta(seconds=20)) == NOW - timedelta(seconds=20)


def test_next_watermark_never_after_now_minus_settle():
    # A pending clip that started "in the future" (clock skew) must not push the watermark past now-settle.
    assert next_watermark(NOW, [NOW + timedelta(minutes=1)], timedelta(seconds=20)) == NOW - timedelta(seconds=20)
