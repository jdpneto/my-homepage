from datetime import datetime

from camera_uploader.model import Clip
from camera_uploader.naming import remote_stem, sanitize_camera


def clip(name="Backyard"):
    return Clip(1, name, "uid1", datetime(2026, 11, 16, 19, 22, 58), datetime(2026, 11, 16, 19, 23, 28),
                frozenset({"motion"}))


def test_sanitize_replaces_separators():
    assert sanitize_camera("Garagem/Porta") == "Garagem_Porta"
    assert sanitize_camera("A\\B") == "A_B"


def test_sanitize_keeps_accents_and_spaces_trims_and_collapses():
    assert sanitize_camera("  Cozinha   é ") == "Cozinha é"


def test_sanitize_control_chars_and_empty():
    assert sanitize_camera("Back\x00yard\n") == "Back_yard"
    assert sanitize_camera(" \n") == "camera"


def test_sanitize_dot_names_cannot_escape():
    assert sanitize_camera("..") == "camera"
    assert sanitize_camera(".") == "camera"


def test_remote_stem():
    assert remote_stem(clip()) == "Backyard/2026-11-16/19-22-58"


def test_remote_stem_suffix():
    assert remote_stem(clip(), 1) == "Backyard/2026-11-16/19-22-58"
    assert remote_stem(clip(), 2) == "Backyard/2026-11-16/19-22-58-2"


def test_remote_stem_sanitizes_camera():
    assert remote_stem(clip("Garagem/Porta")).startswith("Garagem_Porta/")


def test_sanitize_replaces_percent_and_semicolon():
    # Spring's StrictHttpFirewall rejects encoded % and ; with HTTP 400.
    assert sanitize_camera("Sala 100%") == "Sala 100_"
    assert sanitize_camera("Frente;Rua") == "Frente_Rua"
