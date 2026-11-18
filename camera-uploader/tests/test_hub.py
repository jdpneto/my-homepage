from datetime import datetime
from types import SimpleNamespace

from reolink_aio.typings import VOD_trigger

from camera_uploader.hub import clips_from_search, count_untyped

T0 = datetime(2026, 11, 16, 19, 22, 58)
T1 = datetime(2026, 11, 16, 19, 23, 28)
T2 = datetime(2026, 11, 16, 19, 30, 0)
T3 = datetime(2026, 11, 16, 19, 30, 12)


def vod(start, end):
    return SimpleNamespace(start_time=start, end_time=end)


def empty():
    return {t: [] for t in VOD_trigger}


def test_merges_triggers_for_same_clip():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T0, T1)]
    by[VOD_trigger.PERSON] = [vod(T0, T1)]
    clips = clips_from_search(1, "Backyard", "uid1", by)
    assert len(clips) == 1
    c = clips[0]
    assert (c.channel, c.camera_name, c.camera_id, c.start, c.end) == (1, "Backyard", "uid1", T0, T1)
    assert c.triggers == frozenset({"motion", "person"})


def test_sorted_by_start_and_distinct_clips_kept():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T2, T3), vod(T0, T1)]
    clips = clips_from_search(0, "Garage", "uid0", by)
    assert [c.start for c in clips] == [T0, T2]


def test_same_start_different_end_are_two_clips():
    by = empty()
    by[VOD_trigger.MOTION] = [vod(T0, T1), vod(T0, T3)]
    assert len(clips_from_search(0, "Garage", "uid0", by)) == 2


def test_empty_search_returns_empty():
    assert clips_from_search(0, "Garage", "uid0", empty()) == []


def test_count_untyped_counts_only_none_entries():
    by_time = {"20261116192258": VOD_trigger.MOTION, "20261116193000": VOD_trigger.NONE,
               "20261116194000": VOD_trigger.NONE}
    assert count_untyped(by_time) == 2
