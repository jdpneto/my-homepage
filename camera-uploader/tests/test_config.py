from pathlib import Path

import pytest

from camera_uploader.config import Config, ConfigError, load_config
from conftest import write_config


def test_load_config_full(config_path):
    cfg = load_config(config_path)
    assert cfg.hub_host == "192.168.1.24"
    assert cfg.hub_user == "admin"
    assert cfg.hub_password == "secret"
    assert cfg.webdav_url == "https://cloud.davidneto.eu/"
    assert cfg.webdav_user == "cameras"
    assert cfg.poll_seconds == 30
    assert cfg.settle_seconds == 20
    assert cfg.backfill_days == 14
    assert cfg.max_attempts == 5
    assert cfg.state_path == Path("/usr/local/var/camera-uploader/state.sqlite3")
    assert cfg.tmp_dir == Path("/usr/local/var/camera-uploader/tmp")
    assert cfg.log_path == Path("/usr/local/var/log/camera-uploader/uploader.log")
    assert cfg.ffmpeg == "/opt/homebrew/bin/ffmpeg"


def test_uploader_section_defaults_when_omitted(tmp_path):
    cfg = load_config(write_config(tmp_path, drop="uploader.poll_seconds"))
    assert cfg.poll_seconds == 30


def test_backfill_capped_at_14_days(tmp_path, caplog):
    import logging

    from camera_uploader.config import MAX_BACKFILL_DAYS
    from conftest import EXAMPLE

    assert MAX_BACKFILL_DAYS == 14
    # Clips older than the VPS keeps (14 days) would only be re-uploaded and deleted again.
    path = write_config(tmp_path)
    path.write_text(path.read_text().replace("backfill_days = 14", "backfill_days = 30"))
    with caplog.at_level(logging.WARNING, logger="camera_uploader"):
        cfg = load_config(path)
    assert cfg.backfill_days == 14
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
    assert len(warnings) == 1 and "30" in warnings[0].getMessage() and "14" in warnings[0].getMessage()

    # At or below the cap: kept as is, no warning. Omitted: 14. The example says 14.
    for days in (14, 7, 1):
        caplog.clear()
        path.write_text(EXAMPLE.read_text().replace('password = "CHANGE_ME"', 'password = "x"')
                        .replace("backfill_days = 14", f"backfill_days = {days}"))
        assert load_config(path).backfill_days == days
        assert not [r for r in caplog.records if r.levelno >= logging.WARNING]
    assert load_config(write_config(tmp_path, drop="uploader.backfill_days")).backfill_days == 14
    assert Config.__dataclass_fields__["backfill_days"].default == 14
    assert "backfill_days = 14" in EXAMPLE.read_text()


def test_version_is_0_3_2():
    import tomllib

    from camera_uploader import __version__

    pyproject = Path(__file__).resolve().parent.parent / "pyproject.toml"
    assert __version__ == "0.3.2"
    assert tomllib.loads(pyproject.read_text())["project"]["version"] == "0.3.2"


def test_missing_key_names_it(tmp_path):
    with pytest.raises(ConfigError, match=r"webdav\.password"):
        load_config(write_config(tmp_path, drop="webdav.password"))


def test_missing_file_is_config_error(tmp_path):
    with pytest.raises(ConfigError, match="not found"):
        load_config(tmp_path / "nope.toml")


def test_repr_masks_passwords(config_path):
    text = repr(load_config(config_path))
    assert "secret" not in text
    assert "***" in text


def test_config_is_frozen(config_path):
    cfg = load_config(config_path)
    with pytest.raises(Exception):
        cfg.hub_host = "x"  # type: ignore[misc]
    assert isinstance(cfg, Config)


def test_render_config_roundtrips_awkward_passwords(tmp_path):
    from camera_uploader.config import render_config
    from conftest import EXAMPLE

    hub_pw = 'a"b\\c$v5wE é'
    dav_pw = "x'y\tz"
    path = tmp_path / "c.toml"
    path.write_text(render_config(EXAMPLE.read_text(), hub_pw, dav_pw))
    cfg = load_config(path)
    assert cfg.hub_password == hub_pw
    assert cfg.webdav_password == dav_pw
    assert cfg.hub_host == "192.168.1.24" and cfg.webdav_user == "cameras"


def test_render_config_requires_both_placeholders():
    from camera_uploader.config import render_config

    with pytest.raises(ConfigError):
        render_config('[hub]\npassword = "already-set"\n', "a", "b")
