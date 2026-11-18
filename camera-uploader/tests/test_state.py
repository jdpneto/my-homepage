from datetime import datetime

from camera_uploader.state import StateStore


def test_new_key_is_new(tmp_path):
    assert StateStore(tmp_path / "s.db").status("k1") == "new"


def test_mark_uploaded_persists_across_instances(tmp_path):
    path = tmp_path / "s.db"
    StateStore(path).mark_uploaded("k1", "Backyard/2026-11-16/19-22-58")
    assert StateStore(path).status("k1") == "uploaded"


def test_record_failure_turns_failed_exactly_on_max_attempts(tmp_path):
    store = StateStore(tmp_path / "s.db")
    results = [store.record_failure("k1", f"boom {i}", max_attempts=5) for i in range(5)]
    assert results == [False, False, False, False, True]
    assert store.status("k1") == "failed"


def test_failures_below_max_stay_new(tmp_path):
    store = StateStore(tmp_path / "s.db")
    store.record_failure("k1", "boom", max_attempts=5)
    assert store.status("k1") == "new"


def test_upload_after_failures_wins(tmp_path):
    store = StateStore(tmp_path / "s.db")
    store.record_failure("k1", "boom", max_attempts=5)
    store.mark_uploaded("k1", "Garage/2026-11-16/18-50-49")
    assert store.status("k1") == "uploaded"


def test_stem_taken_by_other(tmp_path):
    store = StateStore(tmp_path / "s.db")
    store.mark_uploaded("k1", "Backyard/2026-11-16/19-22-58")
    assert store.stem_taken_by_other("Backyard/2026-11-16/19-22-58", "k1") is False
    assert store.stem_taken_by_other("Backyard/2026-11-16/19-22-58", "k2") is True
    assert store.stem_taken_by_other("Backyard/2026-11-16/19-22-59", "k2") is False


def test_watermark_roundtrip(tmp_path):
    path = tmp_path / "s.db"
    assert StateStore(path).get_watermark() is None
    StateStore(path).set_watermark(datetime(2026, 11, 16, 19, 22, 58))
    assert StateStore(path).get_watermark() == datetime(2026, 11, 16, 19, 22, 58)


def test_creates_parent_directory(tmp_path):
    StateStore(tmp_path / "deep" / "dir" / "s.db").mark_uploaded("k", "s")
    assert (tmp_path / "deep" / "dir" / "s.db").exists()
