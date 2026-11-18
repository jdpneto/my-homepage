# Camera Clip Uploader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A service on the home Mac mini that copies every motion clip from the Reolink Home Hub Mini to the VPS (WebDAV user `cameras`, stored on the dedicated 15 GB volume), unattended, without ever changing hub settings.

**Architecture:** An asyncio Python package (`camera-uploader/` in this repo) runs a sequential loop: search the hub for clips in a time window → skip ones in a local SQLite state → download via `reolink-aio` (Baichuan) → remux fragmented MP4 to fast-start MP4 with ffmpeg → `MKCOL`/`PUT` the `.mp4` then a `.json` sidecar over WebDAV → mark uploaded. A `launchd` LaunchDaemon runs it at boot as user `davidneto`. On the VPS, `/mnt/HC_Volume_107526857/cameras` is bind-mounted to `/app/webdav/cameras`, and a marker file proves the volume is mounted.

**Tech Stack:** Python 3.13 (Homebrew `python@3.13`), `reolink-aio==0.23.1`, `httpx`, stdlib `sqlite3`/`tomllib`/`logging`, ffmpeg (Homebrew), pytest + pytest-asyncio; macOS `launchd`; Docker Compose on the VPS.

**Spec:** `docs/superpowers/specs/2026-11-18-camera-uploader-design.md`

## Global Constraints

- **Read-only towards the hub.** No `reolink-aio` method that changes device state may ever execute (spec §6.3, §10.1). The guard (Task 3) is installed before any `Host` is constructed.
- Hub: `192.168.1.24`, user from config (default `admin`), password only from config. Channels: iterate whatever the hub reports (currently 0 `Garage`, 1 `Backyard`).
- Never request live streams. Download with `stream="main"`.
- Poll every **30 s**; settle delay **20 s** (clip end must be ≥ 20 s in the past); first-run backfill **30 days**; catch-up window capped at **30 days**. All configurable.
- Remux command: `ffmpeg -i <in> -c copy -movflags +faststart <out>` (no re-encode).
- Remote layout (WebDAV paths, relative to the `cameras` user root): `<Camera>/<YYYY-MM-DD>/<HH-MM-SS>.mp4` and `.json` alongside; hub-local start time.
- Upload `.mp4` first, then `.json`; mark uploaded only after **both** return 201/204. Always send `Content-Length` (no chunked uploads).
- WebDAV base URL `https://cloud.davidneto.eu/`, user `cameras`, quota **14336 MB**. 401 → back off **15 min**. 507 → stop the cycle, retry later.
- Keep one hub session across cycles; re-login only after a failure.
- Never log passwords. Secrets live only in `/usr/local/etc/camera-uploader/config.toml` (mode 600, owner `davidneto`) on the Mac mini.
- Mac mini paths: code `/opt/camera-uploader` (venv at `/opt/camera-uploader/venv`), config `/usr/local/etc/camera-uploader/config.toml`, state `/usr/local/var/camera-uploader/state.sqlite3`, temp `/usr/local/var/camera-uploader/tmp`, logs `/usr/local/var/log/camera-uploader/uploader.log` (rotating 10 MB × 5). LaunchDaemon label `eu.davidneto.camera-uploader`, runs as `davidneto`.
- Never use `/usr/bin/python3` on the Mac mini (stub without Command Line Tools); use `/opt/homebrew/bin/python3.13`.
- VPS: volume `/mnt/HC_Volume_107526857`, marker `/mnt/HC_Volume_107526857/cameras/volume-marker.txt` (not a dotfile, so WebDAV listings can't hide it). Clips must never land on the root disk.
- Commits: no `Co-Authored-By` lines (repo CLAUDE.md).

## Review Focus

1. **Hub session dies** (hub reboot, firmware update, idle timeout) → the next cycle logs a warning, logs out, re-creates the session and continues; no crash loop. Test in Task 9.
2. **Camera names with `/`, accents or spaces** (e.g. `Garagem/Porta`, `Cozinha é`) → sanitised folder name, percent-encoded URL, upload succeeds. Tests in Task 5 and Task 7.
3. **A clip that can never be processed** (download returns 0 bytes, ffmpeg fails) → retried on later cycles, then after **5** failed attempts marked `failed`, logged as an error, and no longer holds back the watermark. Test in Task 8.
4. **Leftover temp files after a crash or power cut** → the temp dir is emptied at startup, so the Mac mini's disk never fills. Test in Task 9.
5. **Clocks going back (DST) / two clips in the same second** → the second clip gets a `-2` suffix instead of overwriting; the sidecar records the UTC offset. Tests in Task 5 and Task 8.

---

### Task 1: VPS storage on the 15 GB volume

**Files:**
- Modify: `docker-compose.yml` (service `app`, `volumes:`)
- Modify: `scripts/deploy.sh` (remote block, before `docker compose up -d`)

**Interfaces:**
- Produces: inside the app container, `/app/webdav/cameras` is the volume directory; WebDAV path `volume-marker.txt` exists for user `cameras` (Task 7 checks it).

- [ ] **Step 1: Create the directory and marker on the VPS (one-time, over SSH)**

Run: `ssh -i ~/.ssh/bordofix-deploy root@davidneto.eu 'mountpoint -q /mnt/HC_Volume_107526857 && mkdir -p /mnt/HC_Volume_107526857/cameras && echo "camera clips live on the 15 GB volume - do not delete" > /mnt/HC_Volume_107526857/cameras/volume-marker.txt && ls -la /mnt/HC_Volume_107526857/cameras'`
Expected: `volume-marker.txt` listed.

- [ ] **Step 2: Add the bind mount** — in `docker-compose.yml` `app.volumes`, after `- webdav_data:/app/webdav`, add `- /mnt/HC_Volume_107526857/cameras:/app/webdav/cameras`.

- [ ] **Step 3: Add the deploy guard** — in `scripts/deploy.sh`'s remote heredoc, before `echo ">>> starting stack"`, abort with a clear message unless `mountpoint -q /mnt/HC_Volume_107526857` and `test -f /mnt/HC_Volume_107526857/cameras/volume-marker.txt` both succeed.

- [ ] **Step 4: Verify locally**

Run: `bash -n scripts/deploy.sh && docker compose config | grep -A2 'HC_Volume'`
Expected: no syntax error; the bind mount appears under `app`.

- [ ] **Step 5: Commit, push, deploy** (deploy is from the MacBook with Surfshark off)

```bash
git add docker-compose.yml scripts/deploy.sh
git commit -m "feat(cameras): store camera clips on the dedicated 15 GB volume"
git push origin main && scripts/deploy.sh
```
Expected: deploy health checks OK.

- [ ] **Step 6: Verify on the VPS**

Run: `ssh … root@davidneto.eu 'cd /root/my-homepage && docker compose exec -T app cat /app/webdav/cameras/volume-marker.txt && docker compose exec -T app sh -c "df -h /app/webdav/cameras | tail -1"'`
Expected: marker text; filesystem `/dev/sdb`, size ~15G.

- [ ] **Step 7: David creates WebDAV user `cameras`** at `https://davidneto.eu/admin/webdav-users`, quota **14336** MB, and stores the password where only he sees it. Then verify: `curl -s -o /dev/null -w '%{http_code}' -u cameras:<pw> -X PROPFIND -H 'Depth: 0' https://cloud.davidneto.eu/volume-marker.txt` → `207` (David runs it, so the password stays out of the transcript).

---

### Task 2: Package skeleton, config and the `Clip` model

**Files:**
- Create: `camera-uploader/pyproject.toml`, `camera-uploader/src/camera_uploader/__init__.py`, `config.py`, `model.py`, `camera-uploader/deploy/config.example.toml`, `camera-uploader/README.md`
- Test: `camera-uploader/tests/test_config.py`, `tests/test_model.py`

**Interfaces:**
- Produces:
  - `camera_uploader.__version__: str = "0.1.0"`
  - `@dataclass(frozen=True) class Config` with fields: `hub_host: str`, `hub_user: str`, `hub_password: str`, `webdav_url: str`, `webdav_user: str`, `webdav_password: str`, `poll_seconds: int = 30`, `settle_seconds: int = 20`, `backfill_days: int = 30`, `max_attempts: int = 5`, `state_path: Path`, `tmp_dir: Path`, `log_path: Path`, `ffmpeg: str = "/opt/homebrew/bin/ffmpeg"`; `__repr__` masks both passwords as `***`.
  - `load_config(path: Path) -> Config` — TOML sections `[hub]`, `[webdav]`, `[uploader]`, `[paths]`; raises `ConfigError` naming the missing key.
  - `@dataclass(frozen=True) class Clip`: `channel: int`, `camera_name: str`, `camera_id: str`, `start: datetime` (naive, hub local), `end: datetime`, `triggers: frozenset[str]`; property `key -> str` = `f"{camera_id}|{start:%Y%m%dT%H%M%S}|{end:%Y%m%dT%H%M%S}"`.

- [ ] **Step 1: Write failing tests**

```python
def test_load_config_full(tmp_path):        # all sections → values parsed, defaults applied
    cfg = load_config(write_example(tmp_path))
    assert cfg.hub_host == "192.168.1.24" and cfg.poll_seconds == 30 and cfg.settle_seconds == 20
def test_missing_key_names_it(tmp_path):     # drop [webdav].password
    with pytest.raises(ConfigError, match="webdav.password"): load_config(...)
def test_repr_masks_passwords(tmp_path):
    assert "secret" not in repr(load_config(...))
def test_clip_key_stable():
    c = Clip(0, "Garage", "uid0", datetime(2026,11,16,18,50,49), datetime(2026,11,16,18,51,5), frozenset({"motion"}))
    assert c.key == "uid0|20261116T185049|20261116T185105"
```

- [ ] **Step 2: Run** `cd camera-uploader && python3.13 -m venv .venv && .venv/bin/pip install -e '.[dev]' && .venv/bin/pytest -q` → FAIL (imports missing).
- [ ] **Step 3: Implement** `pyproject.toml` (src layout, `requires-python = ">=3.13"`, deps `reolink-aio==0.23.1`, `httpx>=0.27`, dev extra `pytest`, `pytest-asyncio` with `asyncio_mode = "auto"`; console script `camera-uploader = camera_uploader.main:cli`), `config.py`, `model.py`, `config.example.toml` (placeholders `CHANGE_ME`), README with run/test commands. Add `camera-uploader/.venv/` to the repo `.gitignore`.
- [ ] **Step 4: Run** `.venv/bin/pytest -q` → PASS.
- [ ] **Step 5: Commit** `git add camera-uploader .gitignore && git commit -m "feat(camera-uploader): package skeleton, config and Clip model"`

---

### Task 3: Read-only guard for `reolink-aio`

**Files:**
- Create: `camera-uploader/src/camera_uploader/guard.py`
- Test: `camera-uploader/tests/test_guard.py`

**Interfaces:**
- Produces: `class HubWriteBlocked(RuntimeError)`; `install_read_only_guard() -> None` (idempotent); `BLOCKED = re.compile(r"^(set_|reboot|send_setting|update_firmware|upload_firmware|ptz_|play_quick_reply|subscribe|unsubscribe|format)")`.

Approach: for `reolink_aio.api.Host` and `reolink_aio.baichuan.baichuan.Baichuan`, replace every callable class attribute whose name matches `BLOCKED` with an async (or sync, matching the original) function that raises `HubWriteBlocked(f"blocked {cls.__name__}.{name}")` and logs at ERROR. Done at class level so the library's own internal calls (e.g. `Host._login_try_ports` → `Baichuan.set_port_enabled`) are blocked too.

- [ ] **Step 1: Write failing tests**

```python
def test_every_mutating_method_is_blocked():
    install_read_only_guard()
    for cls in (Host, Baichuan):
        names = [n for n in dir(cls) if BLOCKED.match(n) and callable(getattr(cls, n))]
        assert names, cls                      # sanity: the library still has setters
        for n in names:
            assert getattr(getattr(cls, n), "__camera_uploader_blocked__", False), f"{cls.__name__}.{n}"
async def test_set_port_enabled_raises():
    install_read_only_guard()
    bc = Baichuan("192.0.2.1", "u", "p", http_api=MagicMock())
    with pytest.raises(HubWriteBlocked): await bc.set_port_enabled(PortType.rtmp, True)
async def test_host_set_net_port_raises(): ...   # same for Host("192.0.2.1","u","p").set_net_port(...)
def test_install_is_idempotent(): install_read_only_guard(); install_read_only_guard()  # no double-wrapping errors
def test_read_methods_untouched():
    install_read_only_guard()
    assert not getattr(Baichuan.search_vod_type, "__camera_uploader_blocked__", False)
    assert not getattr(Baichuan.download_mp4, "__camera_uploader_blocked__", False)
```

- [ ] **Step 2: Run** `.venv/bin/pytest tests/test_guard.py -q` → FAIL.
- [ ] **Step 3: Implement** `guard.py` as described; mark wrappers with `__camera_uploader_blocked__ = True`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): block every reolink-aio write path"`

---

### Task 4: Hub client

**Files:**
- Create: `camera-uploader/src/camera_uploader/hub.py`
- Test: `camera-uploader/tests/test_hub.py`

**Interfaces:**
- Consumes: `Config`, `Clip`, `install_read_only_guard()`.
- Produces:
  - `class HubError(Exception)`
  - `clips_from_search(channel: int, camera_name: str, camera_id: str, vod_by_trigger: dict) -> list[Clip]` — pure: merges the per-trigger lists returned by `Baichuan.search_vod_type` into one `Clip` per `(start_time, end_time)`, `triggers` = lower-case `VOD_trigger` names of every list it appeared in (e.g. `{"motion","person"}`), sorted by `start`.
  - `class ReolinkHub` with `async connect()`, `async info() -> dict` (`{"model","firmware"}`), `async list_clips(start: datetime, end: datetime) -> list[Clip]` (all channels, all triggers), `async download(clip: Clip, dest: Path) -> int` (bytes written; uses `baichuan.download_mp4(ch, start, end, stream="main", ffmpeg=cfg.ffmpeg)`), `async close()`. `connect()` calls `install_read_only_guard()` before constructing `Host(cfg.hub_host, cfg.hub_user, cfg.hub_password)`, then `get_host_data()`. `camera_id` = `host.camera_uid(ch)` if it isn't `UNKNOWN`, else `f"ch{ch}-{host.camera_model(ch)}"`. Any `ReolinkError`/`OSError`/timeout is re-raised as `HubError`.

- [ ] **Step 1: Write failing tests** with a tiny fake `VOD_file` (attributes `start_time`, `end_time`):

```python
def test_merges_triggers_for_same_clip():
    a = fake_vod(t0, t1); b = fake_vod(t0, t1)
    clips = clips_from_search(1, "Backyard", "uid1", {VOD_trigger.MOTION: [a], VOD_trigger.PERSON: [b]})
    assert len(clips) == 1 and clips[0].triggers == frozenset({"motion", "person"})
def test_sorted_by_start(): ...
def test_empty_search_returns_empty(): assert clips_from_search(0, "Garage", "u", {t: [] for t in VOD_trigger}) == []
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Manual smoke test against the real hub from the MacBook (home LAN, Surfshark off)** — a throwaway script calling `connect()`, `info()`, `list_clips(now-24h, now)`, `download()` of the newest clip; expected: model `Reolink Home Hub Mini`, ≥ 1 clip, file > 0 bytes; and the hub's port list unchanged before/after (compare `baichuan.get_ports()` output — a read).
- [ ] **Step 6: Commit** `git commit -m "feat(camera-uploader): read-only Reolink hub client"`

---

### Task 5: Remux, naming and sidecar

**Files:**
- Create: `src/camera_uploader/remux.py`, `naming.py`, `sidecar.py`
- Test: `tests/test_remux.py`, `tests/test_naming.py`, `tests/test_sidecar.py`

**Interfaces:**
- Produces:
  - `class RemuxError(Exception)`; `remux(src: Path, dst: Path, ffmpeg: str) -> None` — runs the Global Constraints command with a 120 s timeout; raises `RemuxError` (with ffmpeg's last stderr line) on non-zero exit, timeout, or empty output.
  - `sanitize_camera(name: str) -> str` — replace `/`, `\` and control characters with `_`, strip, collapse whitespace, keep accents and spaces, empty → `"camera"`.
  - `remote_stem(clip: Clip, suffix: int = 1) -> str` — `f"{sanitize_camera(name)}/{start:%Y-%m-%d}/{start:%H-%M-%S}"` plus `-{suffix}` when `suffix > 1`; `.mp4`/`.json` are appended by callers.
  - `build_sidecar(clip: Clip, hub_info: dict, uploader_version: str, tz: tzinfo) -> dict` with keys `camera_name`, `camera_id`, `channel`, `start`, `end` (ISO 8601 with the local UTC offset via `tz`), `duration_seconds`, `triggers` (sorted list), `hub_model`, `hub_firmware`, `uploader_version`.

- [ ] **Step 1: Write failing tests**

```python
def test_remux_makes_faststart(tmp_path):   # make a fragmented mp4 with ffmpeg lavfi testsrc (1 s) + -movflags frag_keyframe+empty_moov
    remux(frag, out, "ffmpeg"); assert moov_before_mdat(out)   # parse top-level atoms
def test_remux_bad_input_raises(tmp_path):   # 100 random bytes
    with pytest.raises(RemuxError): remux(junk, out, "ffmpeg")
def test_sanitize(): assert sanitize_camera("Garagem/Porta") == "Garagem_Porta" and sanitize_camera("Cozinha é ") == "Cozinha é" and sanitize_camera(" \n") == "camera"
def test_remote_stem(): assert remote_stem(clip_backyard_192258) == "Backyard/2026-11-16/19-22-58"
def test_remote_stem_suffix(): assert remote_stem(clip, 2).endswith("19-22-58-2")
def test_sidecar_has_offset_and_triggers():
    s = build_sidecar(clip, {"model": "Reolink Home Hub Mini", "firmware": "v3.3.0.609_26092101"}, "0.1.0", ZoneInfo("Europe/Lisbon"))
    assert s["start"] == "2026-11-16T19:22:58+00:00" and s["triggers"] == ["motion", "person"] and s["duration_seconds"] == 30
```

- [ ] **Step 2: Run** → FAIL (skip `test_remux_*` with a clear reason if `ffmpeg` isn't on PATH). **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): remux to fast-start mp4, remote naming, sidecar"`

---

### Task 6: State store and time window

**Files:**
- Create: `src/camera_uploader/state.py`, `window.py`
- Test: `tests/test_state.py`, `tests/test_window.py`

**Interfaces:**
- Produces:
  - `class StateStore(path: Path)` (SQLite, WAL, one transaction per write):
    - `status(key: str) -> Literal["new","uploaded","failed"]`
    - `mark_uploaded(key: str, remote_stem: str) -> None`
    - `record_failure(key: str, error: str, max_attempts: int) -> bool` — increments attempts; returns `True` when it just became `failed`
    - `stem_taken_by_other(stem: str, key: str) -> bool`
    - `get_watermark() -> datetime | None`, `set_watermark(dt: datetime) -> None`
  - `compute_window(now: datetime, watermark: datetime | None, backfill_days: int, overlap: timedelta = timedelta(minutes=10)) -> tuple[datetime, datetime]` — start = `now - backfill_days` if no watermark, else `max(watermark - overlap, now - backfill_days)`; end = `now`.
  - `next_watermark(now: datetime, pending_starts: list[datetime], settle: timedelta) -> datetime` — `min(pending_starts)` if any, else `now - settle`.

- [ ] **Step 1: Write failing tests** — first-run window is 30 days; window with watermark starts 10 min before it; catch-up capped at 30 days after a 40-day outage; `next_watermark` with and without pending; `mark_uploaded` persists across a new `StateStore` on the same file; `record_failure` returns `True` exactly on the 5th call and status becomes `failed`; `stem_taken_by_other` is `False` for the same key, `True` for a different key.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): sqlite state and catch-up window"`

---

### Task 7: WebDAV client

**Files:**
- Create: `src/camera_uploader/webdav.py`
- Test: `tests/test_webdav.py` (uses `httpx.MockTransport`)

**Interfaces:**
- Produces:
  - Errors: `class WebDavError(Exception)`, `AuthError(WebDavError)` (401/403), `QuotaFullError(WebDavError)` (507), `TransientError(WebDavError)` (5xx other than 507, timeouts, connection errors).
  - `class WebDavClient(base_url: str, user: str, password: str, transport: httpx.AsyncBaseTransport | None = None)`:
    - `async ensure_dirs(stem: str) -> None` — `MKCOL` each parent collection of `stem` in order; 201 and 405 are success.
    - `async put_file(path: str, src: Path, content_type: str) -> None` — streams the file with an explicit `Content-Length`; 201/204 success.
    - `async put_bytes(path: str, data: bytes, content_type: str) -> None`
    - `async exists(path: str) -> bool` — `PROPFIND` `Depth: 0`; 207 → True, 404 → False.
    - `async aclose() -> None`
  - Every path segment is percent-encoded (`urllib.parse.quote(segment, safe="")`); timeouts 30 s connect, 300 s read/write.

- [ ] **Step 1: Write failing tests** — MKCOL sequence for `"Backyard/2026-11-16/19-22-58"` is `Backyard/` then `Backyard/2026-11-16/`; 405 tolerated; PUT sends `Content-Length` equal to the file size and no `Transfer-Encoding: chunked`; 507 → `QuotaFullError`; 401 → `AuthError`; 502 → `TransientError`; `httpx.ConnectError` → `TransientError`; path `Cozinha é/…` is sent as `Cozinha%20%C3%A9/…`; `exists` maps 207/404.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): WebDAV client with quota/auth error mapping"`

---

### Task 8: One upload cycle

**Files:**
- Create: `src/camera_uploader/cycle.py`
- Test: `tests/test_cycle.py` (fake hub, fake WebDAV, real `StateStore` on `tmp_path`, fake `remux` that copies)

**Interfaces:**
- Consumes: `ReolinkHub`-shaped object (`list_clips`, `download`, `info`), `WebDavClient`-shaped object, `StateStore`, `compute_window`, `next_watermark`, `remote_stem`, `build_sidecar`, `remux`.
- Produces: `@dataclass class CycleResult: uploaded: int, skipped: int, pending: int, failed: int`; `async run_cycle(hub, dav, store: StateStore, cfg: Config, now: datetime, remux_fn=remux, tz=None) -> CycleResult`.

Behaviour, in order: (1) `dav.exists("volume-marker.txt")` must be True, else raise `WebDavError("volume marker missing - refusing to upload")` before any PUT; (2) window from `compute_window`; (3) `hub.list_clips(window)`; (4) for each clip oldest first: skip `uploaded`/`failed`; if `clip.end > now - settle` count as pending; else download to `cfg.tmp_dir`, remux, pick the first `suffix` whose stem isn't `stem_taken_by_other`, `ensure_dirs`, `put_file(.mp4)`, `put_bytes(.json)`, `mark_uploaded`; delete temp files in `finally`; (5) `HubError`/`RemuxError`/empty download → `record_failure` (log ERROR when it turns `failed`) and continue; `QuotaFullError`, `AuthError`, `TransientError` propagate immediately (stop the cycle); (6) `set_watermark(next_watermark(now, starts of clips still new/pending, settle))`.

- [ ] **Step 1: Write failing tests**
  - `test_uploads_new_clip_mp4_then_json` — calls in order: MKCOL…, PUT `.mp4`, PUT `.json`; state `uploaded`.
  - `test_skips_already_uploaded` — second run uploads nothing.
  - `test_unsettled_clip_is_pending_not_uploaded` — end 5 s ago → `pending == 1`, no PUT; watermark ≤ its start.
  - `test_crash_after_mp4_before_json_reuploads_both` — fake `put_bytes` raises `TransientError` once → state stays `new`; next run PUTs both again.
  - `test_quota_full_stops_cycle` — 507 on the first clip → `QuotaFullError` raised, second clip never downloaded.
  - `test_missing_marker_refuses` — `exists` False → error, zero PUTs.
  - `test_bad_clip_fails_after_5_attempts_and_releases_watermark` — remux always raises; after 5 cycles status `failed`, watermark moves past it, later clips still upload.
  - `test_same_second_collision_gets_suffix` — two clips, same camera and start second, different end → stems `…/19-22-58` and `…/19-22-58-2`.
  - `test_temp_files_removed_after_success_and_failure`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): upload cycle with exactly-once-ish delivery"`

---

### Task 9: Main loop, backoff and logging

**Files:**
- Create: `src/camera_uploader/main.py`
- Test: `tests/test_main.py`

**Interfaces:**
- Consumes: everything above.
- Produces: `cli()` (console entry; `--config PATH` default `/usr/local/etc/camera-uploader/config.toml`, `--once` runs one cycle and exits non-zero on error); `async run_forever(cfg, hub_factory, dav_factory, sleep=asyncio.sleep, clock=datetime.now)`; `backoff_seconds(error: Exception, consecutive: int) -> float` — `AuthError` → 900; `QuotaFullError` → 600; `HubError`/`TransientError` → `min(30 * 2**(consecutive-1), 300)`; anything else → 300; success resets.

Behaviour: on start, `cfg.tmp_dir` is emptied and created; logging to `RotatingFileHandler(cfg.log_path, maxBytes=10_000_000, backupCount=5)` + stderr, format `%(asctime)s %(levelname)s %(name)s %(message)s`; one INFO line per uploaded clip (camera, start, MB, seconds); one hub session reused; after a `HubError` the hub is `close()`d and re-created on the next cycle; cycles never overlap (sequential loop); sleep `poll_seconds` between successful cycles.

- [ ] **Step 1: Write failing tests** — `backoff_seconds` table above; `test_hub_error_recreates_session` (factory called twice after one failure, loop continues); `test_tmp_dir_emptied_at_start`; `test_password_never_logged` (caplog over a cycle with an `AuthError` contains neither password).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (whole suite: `.venv/bin/pytest -q`).
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): main loop with backoff and rotating logs"`

---

### Task 10: macOS install as a LaunchDaemon

**Files:**
- Create: `camera-uploader/deploy/install.sh`, `deploy/uninstall.sh`, `deploy/eu.davidneto.camera-uploader.plist`

**Interfaces:**
- Consumes: package from Tasks 2–9, `deploy/config.example.toml`.
- Produces: running daemon `system/eu.davidneto.camera-uploader`.

`install.sh` (run with `sudo` on the Mac mini; idempotent): ensures `brew install python@3.13 ffmpeg` (run as `davidneto`, not root); rsyncs the package to `/opt/camera-uploader`; creates/upgrades the venv with `/opt/homebrew/bin/python3.13`; creates state/tmp/log dirs owned by `davidneto`; if no config exists, copies the example and **prompts interactively** for hub and WebDAV passwords with `read -s` (writes them, `chmod 600`, owner `davidneto`); warns if `fdesetup status` is not Off; checks `pmset -g` for `sleep 0` and `autorestart 1` and warns otherwise; runs `camera-uploader --once` as `davidneto` and aborts if it fails; installs the plist to `/Library/LaunchDaemons` (root:wheel 644) and `launchctl bootout`/`bootstrap system`. Plist: `UserName davidneto`, `RunAtLoad true`, `KeepAlive true`, `ThrottleInterval 30`, `ProgramArguments` = venv `camera-uploader --config …`, stdout/stderr to the log dir. `uninstall.sh` boots out and removes the plist (keeps config, state and logs unless `--purge`).

- [ ] **Step 1: Lint** `plutil -lint deploy/eu.davidneto.camera-uploader.plist && bash -n deploy/install.sh deploy/uninstall.sh` (and `shellcheck` if installed) → OK.
- [ ] **Step 2: Copy to the Mac mini** `rsync -a --delete --exclude .venv camera-uploader/ macmini:~/camera-uploader-src/`
- [ ] **Step 3: David runs the installer in his own terminal** (so passwords never enter the chat): `ssh -t macmini 'sudo ~/camera-uploader-src/deploy/install.sh'`. Expected: `--once` succeeds, then `daemon loaded`.
- [ ] **Step 4: Verify** `ssh macmini 'sudo launchctl print system/eu.davidneto.camera-uploader | grep -E "state|pid"; tail -5 /usr/local/var/log/camera-uploader/uploader.log'` → `state = running`, recent cycle lines.
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): launchd install/uninstall for the Mac mini"`

---

### Task 11: End-to-end acceptance on real hardware

No new files; this task proves the spec's goals.

- [ ] **Step 1: Backfill done** — `ssh … root@davidneto.eu 'find /mnt/HC_Volume_107526857/cameras -name "*.mp4" | wc -l; du -sh /mnt/HC_Volume_107526857/cameras'` → clip count ≈ hub's clips for the last 30 days (compare with a `list_clips` count logged at startup); every `.mp4` has a `.json`.
- [ ] **Step 2: Fresh clip latency** — wave at a camera; within ~1 minute after the clip ends a new file appears under today's folder. Open it in a browser via WebDAV → plays and seeks.
- [ ] **Step 3: Hub untouched** — compare `baichuan.get_ports()` (read-only) with the values in spec §3; no setting changed; Reolink phone app still arms/disarms.
- [ ] **Step 4: Reboot test** — `ssh macmini 'sudo shutdown -r now'`; after boot, without logging in, the daemon is running and a new clip still arrives.
- [ ] **Step 5: Outage test** — stop the network on the Mac mini (unplug the cable) for 10 minutes while triggering a clip; reconnect → the missed clip arrives on the next cycles, no duplicates.
- [ ] **Step 6: Root-disk guard** — on the VPS, confirm `df` shows clips only on `/dev/sdb`.
- [ ] **Step 7: Clean up secrets from testing** — David changes the hub `admin` password in the Reolink app, updates it in `/usr/local/etc/camera-uploader/config.toml` (via `sudo` on the Mac mini), `launchctl kickstart -k system/eu.davidneto.camera-uploader`, deletes `~/reolink.txt` on the MacBook.
