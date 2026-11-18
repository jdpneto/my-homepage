from pathlib import Path

import pytest

EXAMPLE = Path(__file__).resolve().parent.parent / "deploy" / "config.example.toml"


def write_config(tmp_path: Path, *, drop: str | None = None) -> Path:
    """Copy the example config with real-looking passwords; optionally drop `section.key`."""
    text = EXAMPLE.read_text().replace('password = "CHANGE_ME"', 'password = "secret"')
    if drop:
        section, key = drop.split(".")
        lines, current = [], None
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith("["):
                current = stripped.strip("[]")
            if current == section and stripped.startswith(f"{key} ="):
                continue
            lines.append(line)
        text = "\n".join(lines)
    path = tmp_path / "config.toml"
    path.write_text(text)
    return path


@pytest.fixture
def config_path(tmp_path):
    return write_config(tmp_path)
