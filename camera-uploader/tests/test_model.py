from datetime import datetime

from camera_uploader.model import Clip


def _clip(**kw):
    base = dict(
        channel=0,
        camera_name="Garage",
        camera_id="uid0",
        start=datetime(2026, 11, 16, 18, 50, 49),
        end=datetime(2026, 11, 16, 18, 51, 5),
        triggers=frozenset({"motion"}),
    )
    base.update(kw)
    return Clip(**base)


def test_clip_key_stable():
    assert _clip().key == "uid0|20261116T185049|20261116T185105"


def test_clip_key_ignores_name_and_triggers():
    # Renaming a camera or reclassifying a clip must not make it look new.
    assert _clip(camera_name="Garagem", triggers=frozenset({"person"})).key == _clip().key


def test_clip_key_differs_by_end():
    assert _clip(end=datetime(2026, 11, 16, 18, 51, 6)).key != _clip().key
