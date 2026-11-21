# Camera Mode Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch the hub between disarm/home/away from `/admin/cameras`; the Mac mini applies it within a cycle.

**Architecture:** Server writes `mode-request.json` (protected from WebDAV writes) at the camera root; the uploader reads it each cycle, applies it through the one allowed hub write (`set_scene` with a listed id ≥ 0), and reports the result in the heartbeat; the page shows pending → applied/failed/expired.

**Tech Stack:** Spring Boot 4.1.2/Java 21, Thymeleaf, vanilla JS; Python 3.13 + reolink-aio 0.23.1 + pytest.

**Spec:** `docs/superpowers/specs/2026-11-21-camera-mode-switch-design.md`

## Global Constraints

- Modes: `disarm`, `home`, `away` only (`off` refused everywhere). Request expiry: 5 min after `requested_at`.
- Request file `mode-request.json` at the camera root: `{"id","mode","requested_at","expires_at"}` (ISO-8601 with offset); heartbeat key `mode_request`: `{"id","status":"applied"|"failed","error","at"}`.
- API: `POST /api/cameras/mode` → 202 `{"id","mode","status":"pending"}`; 400 bad mode; 503 volume missing. `GET /api/cameras/mode` → `{"current","request"}`; request status `pending|applied|failed|expired`.
- WebDAV: PUT/MOVE/COPY/DELETE/PROPPATCH/LOCK on or onto `mode-request.json` refused for every WebDAV user (case-insensitive, like the volume marker).
- Uploader: guard stays on; the only hub write is `switch_mode(name)` → original `Baichuan.set_scene` with an id the hub listed (≥ 0). A request is applied at most once (id in the state DB `meta` table), never after `expires_at`, never breaks a cycle.
- Page copy: "Mode: [Disarm] [Home] [Away]"; "Switching to away…"; "Away ✓"; "Not applied: <reason>"; "Expired — the Mac mini didn't pick it up". Disarm: two taps (`CONFIRM_MIN_MS`/4 s like Delete).
- Commits: no co-credit lines.

## Review Focus

- Mac mini offline for an hour, then back → the old request is not applied (`test_expired_request_is_not_applied`).
- Same request seen on two cycles → applied once (`test_request_applied_once`).
- Request names `off` / an unknown mode / a scene the hub didn't list → not applied, `failed` (`test_unknown_mode_fails_without_hub_write`).
- The cameras WebDAV user tries to PUT `Mode-Request.json` → 403 (`webdavCannotWriteModeRequest`).
- Hub refuses / times out → `failed`, cycle continues and clips still upload (`test_switch_failure_does_not_fail_the_cycle`).

---

### Task 1: Server — request API, WebDAV protection, page switch

**Files:** Create `cameras/CameraModeController.java`, `cameras/ModeRequests.java` (write/read request file, compute status from heartbeat); modify `ClipFiles.java` (read heartbeat `mode_request`), `webdav/WebDavProtectionFilter.java`, `templates/admin/cameras.html`, `static/js/cameras.js`, `static/css/cameras.css`; tests `CameraModeApiIT.java` (new), `ModeRequestsTest.java` (new), `webdav/ProtectedFolderIT.java`, `AdminCamerasPageIT.java`.

**Interfaces:** Produces the request file and API above; consumes heartbeat `mode`/`mode_request` (Task 2).

- [ ] **Step 1: Failing tests:** `ModeRequestsTest`: `writesRequestFileAtomically` (fields, expires = requested + 5 min), `statusPendingAppliedFailedExpired` (heartbeat id match → its status; no match + past expiry → expired; else pending), `newRequestReplacesOld`. `CameraModeApiIT`: `postModeWritesRequest202`, `offAndUnknownAre400`, `needsAdminAndCsrf` (no csrf 403; csrf no user 401), `getModeReportsCurrentAndRequest`, `volumeMissing503`. `ProtectedFolderIT::webdavCannotWriteModeRequest` (PUT/MOVE onto/DELETE `mode-request.json` and `Mode-Request.json` → refused, file unchanged). `AdminCamerasPageIT::modeSwitchMarkup` (`id="mode-switch"`, buttons `data-mode="disarm|home|away"`, current one `aria-pressed="true"` from heartbeat mode) and JS contains `/mode`, `Switching to`, `Expired`.
- [ ] **Step 2: Run** them — Expected: fail.
- [ ] **Step 3: Implement** per Global Constraints; page JS: tap → POST → poll GET every 3 s while pending (stop after 6 min), Disarm armed/confirm like Delete, signed-out handling via the existing `api()` helper.
- [ ] **Step 4: Run** full suite — Expected: green. **Step 5: Commit** `feat(cameras): remote mode switch (request file, API, page)`.

### Task 2: Uploader — apply requests

**Files:** Modify `camera-uploader/src/camera_uploader/{guard,hub,cycle,heartbeat,state,main}.py`; create `camera-uploader/src/camera_uploader/mode_request.py`; tests `test_mode_request.py` (new), `test_guard.py`, `test_hub.py`, `test_cycle.py`, `test_heartbeat.py`, `test_state.py`.

**Interfaces:** Produces `async ReolinkHub.switch_mode(name: str) -> None` (raises `ModeSwitchError` with a short reason); `parse_request(text) -> ModeRequest | None`; `StateStore.get_applied_mode_request()/set_applied_mode_request(id)`; `Heartbeat.set_mode_request_result(dict)` (sent at once); `run_cycle` handles the request after the marker check and before listing clips, recording the result on `CycleResult.mode_request`.

- [ ] **Step 1: Failing tests:** `test_guard.py::test_set_scene_still_blocked_and_switch_mode_is_the_only_way`; `test_hub.py::test_switch_mode_calls_original_set_scene_with_listed_id`, `::test_switch_mode_refuses_off_and_unlisted`; `test_mode_request.py::test_parse_valid / test_parse_garbage_none`; `test_cycle.py::test_request_applied_once`, `::test_expired_request_is_not_applied`, `::test_unknown_mode_fails_without_hub_write`, `::test_switch_failure_does_not_fail_the_cycle`, `::test_no_request_file_no_switch`; `test_heartbeat.py::test_mode_request_result_sent_at_once`; `test_state.py::test_applied_mode_request_roundtrip`.
- [ ] **Step 2: Run** `.venv/bin/pytest -q` — Expected: new tests fail.
- [ ] **Step 3: Implement**; bump version to 0.3.0.
- [ ] **Step 4: Run** — Expected: green. **Step 5: Commit** `feat(uploader): apply remote mode requests (only allowed hub write)`.

### Task 3: Ship

- [ ] Fresh whole-branch review → fix Critical/Important RED→GREEN.
- [ ] Merge, push, deploy server (`scripts/deploy.sh`, never `--full`), rsync uploader to `macmini:~/camera-uploader-src`, David runs `ssh -t macmini 'sudo ~/camera-uploader-src/deploy/install.sh'`.
- [ ] Live check with David: switch to away from the page → "Away ✓" and the Reolink app shows away; switch back to home.
