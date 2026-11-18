# camera-uploader

Copies motion clips from the Reolink Home Hub Mini (192.168.1.24) to the VPS
over WebDAV (user `cameras`, stored on the 15 GB volume). Runs on the Mac mini
as the LaunchDaemon `eu.davidneto.camera-uploader`.

Design: `../docs/superpowers/specs/2026-11-18-camera-uploader-design.md`

## Develop

```bash
/opt/homebrew/bin/python3.13 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
```

## Run once by hand

```bash
.venv/bin/camera-uploader --config /path/to/config.toml --once
```

Config template: `deploy/config.example.toml`. Install on the Mac mini:
`deploy/install.sh` (see the design doc).
