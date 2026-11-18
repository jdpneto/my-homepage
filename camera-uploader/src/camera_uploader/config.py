"""Configuration loaded from a TOML file that only exists on the Mac mini."""

from __future__ import annotations

import json
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class ConfigError(Exception):
    """The config file is missing, unreadable or lacks a required key."""


@dataclass(frozen=True)
class Config:
    hub_host: str
    hub_user: str
    hub_password: str = field(repr=False)
    webdav_url: str
    webdav_user: str
    webdav_password: str = field(repr=False)
    state_path: Path
    tmp_dir: Path
    log_path: Path
    poll_seconds: int = 30
    settle_seconds: int = 20
    backfill_days: int = 30
    max_attempts: int = 5
    ffmpeg: str = "/opt/homebrew/bin/ffmpeg"

    def __repr__(self) -> str:
        return (
            f"Config(hub={self.hub_user}@{self.hub_host} password=***, "
            f"webdav={self.webdav_user}@{self.webdav_url} password=***, "
            f"poll={self.poll_seconds}s settle={self.settle_seconds}s "
            f"backfill={self.backfill_days}d max_attempts={self.max_attempts}, "
            f"state={self.state_path} tmp={self.tmp_dir} log={self.log_path} ffmpeg={self.ffmpeg})"
        )

    __str__ = __repr__


_PLACEHOLDER = 'password = "CHANGE_ME"'


def render_config(template: str, hub_password: str, webdav_password: str) -> str:
    """Fill the two password placeholders ([hub] first, then [webdav]) with safely escaped TOML strings."""
    if template.count(_PLACEHOLDER) != 2:
        raise ConfigError("template must contain exactly two password placeholders ([hub] and [webdav])")
    # A JSON string literal is a valid TOML basic string (same escapes, incl. \uXXXX).
    text = template.replace(_PLACEHOLDER, f"password = {json.dumps(hub_password)}", 1)
    return text.replace(_PLACEHOLDER, f"password = {json.dumps(webdav_password)}", 1)


def _require(data: dict[str, Any], section: str, key: str) -> Any:
    try:
        value = data[section][key]
    except KeyError:
        raise ConfigError(f"missing required config key {section}.{key}") from None
    if value in ("", None):
        raise ConfigError(f"config key {section}.{key} is empty")
    return value


def load_config(path: Path) -> Config:
    path = Path(path)
    try:
        data = tomllib.loads(path.read_text())
    except FileNotFoundError:
        raise ConfigError(f"config file {path} not found") from None
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ConfigError(f"cannot read config file {path}: {exc}") from None

    uploader = data.get("uploader", {})
    paths = data.get("paths", {})
    return Config(
        hub_host=_require(data, "hub", "host"),
        hub_user=_require(data, "hub", "user"),
        hub_password=_require(data, "hub", "password"),
        webdav_url=_require(data, "webdav", "url"),
        webdav_user=_require(data, "webdav", "user"),
        webdav_password=_require(data, "webdav", "password"),
        state_path=Path(_require(data, "paths", "state")),
        tmp_dir=Path(_require(data, "paths", "tmp")),
        log_path=Path(_require(data, "paths", "log")),
        poll_seconds=int(uploader.get("poll_seconds", 30)),
        settle_seconds=int(uploader.get("settle_seconds", 20)),
        backfill_days=int(uploader.get("backfill_days", 30)),
        max_attempts=int(uploader.get("max_attempts", 5)),
        ffmpeg=str(paths.get("ffmpeg", "/opt/homebrew/bin/ffmpeg")),
    )
