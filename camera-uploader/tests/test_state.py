from datetime import datetime, timedelta

from camera_uploader.state import StateStore


def test_new_key_is_new(tmp_path):
    assert StateStore(tmp_path / "s.db").status("k1") == "new"


def test_mark_uploaded_persists_across_instances(tmp_path):
    path = tmp_path / "s.db"
    StateStore(path).mark_uploaded("k1", "Backyard/2026-11-16/19-22-58")
    assert StateStore(path).status("k1") == "uploaded"


def test_record_failure_turns_failed_exactly_on_max_attempts(tmp_path):
    store = StateStore(tmp_path / "s.db")
    results = [store.record_failure("k1", f"boom {i}", max_attempts=5, give_up_after=timedelta(0))
               for i in range(5)]
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


def test_not_given_up_before_give_up_after_has_passed(tmp_path):
    store = StateStore(tmp_path / "s.db")
    t0 = datetime(2026, 11, 18, 21, 0, 0)
    for i in range(10):  # many quick failures, e.g. during a hub outage
        assert store.record_failure("k1", "boom", max_attempts=5, give_up_after=timedelta(hours=6),
                                    now=t0 + timedelta(minutes=i)) is False
    assert store.status("k1") == "new"
    assert store.record_failure("k1", "boom", max_attempts=5, give_up_after=timedelta(hours=6),
                                now=t0 + timedelta(hours=6, minutes=1)) is True
    assert store.status("k1") == "failed"


def test_applied_mode_request_roundtrip(tmp_path):
    path = tmp_path / "s.db"
    assert StateStore(path).get_applied_mode_request() is None
    assert StateStore(path).get_mode_request_result() is None
    StateStore(path).set_applied_mode_request("req-1")
    assert StateStore(path).get_applied_mode_request() == "req-1"
    result = {"id": "req-1", "status": "applied", "error": None, "at": "2026-11-21T10:00:30+00:00"}
    StateStore(path).set_applied_mode_request("req-1", result)
    assert StateStore(path).get_applied_mode_request() == "req-1"
    assert StateStore(path).get_mode_request_result() == result
    StateStore(path).set_applied_mode_request("req-2")
    assert StateStore(path).get_applied_mode_request() == "req-2"
    assert StateStore(path).get_watermark() is None  # separate meta rows


def test_known_cameras_roundtrip(tmp_path):
    from camera_uploader.model import Camera

    path = tmp_path / "s.db"
    assert StateStore(path).get_known_cameras() == {}
    StateStore(path).set_known_cameras({
        0: Camera(0, "Garage", "9527000O0NWN1LLO", online=True),
        1: Camera(1, "Pátio dos fundos", "9527000OBACKYARD", online=False),
    })
    # Survives a restart; the online status is only the latest refresh's, never stored.
    assert StateStore(path).get_known_cameras() == {
        0: Camera(0, "Garage", "9527000O0NWN1LLO"),
        1: Camera(1, "Pátio dos fundos", "9527000OBACKYARD"),
    }
    StateStore(path).set_known_cameras({0: Camera(0, "Garage door", "9527000O0NWN1LLO")})
    assert StateStore(path).get_known_cameras() == {0: Camera(0, "Garage door", "9527000O0NWN1LLO")}
    assert StateStore(path).get_watermark() is None  # separate meta rows


def test_per_camera_watermark_roundtrip(tmp_path):
    path = tmp_path / "s.db"
    assert StateStore(path).get_camera_watermark(1) is None
    StateStore(path).set_camera_watermark(1, datetime(2026, 11, 21, 15, 23, 0))
    StateStore(path).set_camera_watermark(0, datetime(2026, 11, 22, 2, 1, 0))
    assert StateStore(path).get_camera_watermark(1) == datetime(2026, 11, 21, 15, 23, 0)
    assert StateStore(path).get_camera_watermark(0) == datetime(2026, 11, 22, 2, 1, 0)
    assert StateStore(path).get_camera_watermark(2) is None
    assert StateStore(path).get_watermark() is None  # the old global watermark is a different row
    StateStore(path).set_watermark(datetime(2026, 11, 20, 0, 0, 0))
    assert StateStore(path).get_camera_watermark(1) == datetime(2026, 11, 21, 15, 23, 0)
