# Person-While-Away Push Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Push "Person · <Camera> · <HH:mm>" to ntfy when a person clip is recorded in away mode, at most one push an hour (then one summary).

**Architecture:** The Mac mini uploader reads the hub's active mode (read-only) each cycle and stamps it into each clip's sidecar and the heartbeat. The server's indexer hands newly added clips to a `PersonPushNotifier` that applies the away/person/30-min rule and the hourly cap, persisting its state in `camera_setting`; a one-minute job sends the summary.

**Tech Stack:** Python 3.13 + reolink-aio 0.23.1 + pytest (uploader); Spring Boot 4.1.2 / Java 21 / JUnit + MockMvc (server).

**Spec:** `docs/superpowers/specs/2026-11-21-away-person-push-design.md`

## Global Constraints

- The uploader's read-only guard stays fully on; only `get_scene` / `get_scene_info` are added. A mode read failure never fails a cycle: mode becomes `None`.
- Mode values: hub name lower-cased (`away`, `home`, `disarm`, `off`) or `null`.
- Push rule: triggers contain `person` AND sidecar mode `away` AND clip start ≥ indexing time − 30 min.
- Cap: one push per rolling hour; clips inside the hour are counted per camera and sent as one summary when the hour ends; the summary starts the next hour.
- Copy: title `Person · <Camera> · <HH:mm>` (clip's own offset), body `Away mode`, click `https://davidneto.eu/admin/cameras?camera=<Camera>&trigger=person` (URL-encoded); summary title `<N> more person clip(s) while away`, body `Garage 3, Backyard 1` (count desc, then name), click `…/admin/cameras?trigger=person`. Priority `high`, tag `bust_in_silhouette`.
- Muted (switch off or Mute 8 h) → nothing sent, pending counts dropped.
- State persisted in `camera_setting` (survives restarts). Text only, never images. Never log the ntfy topic.
- Commits: no co-credit lines (repo CLAUDE.md).

## Review Focus

- Hub mode read throws or times out → clip still uploads with `"mode": null`, heartbeat `mode` null, cycle result unaffected (`test_mode_failure_does_not_fail_the_cycle`).
- App restart in the middle of an hour with pending clips → no second immediate push, summary still sent (`stateSurvivesRestart`).
- Re-index / outage backlog of old away+person clips → no push (`oldClipsNeverPush`).
- Muted, then unmuted within the hour → the clips seen while muted are not summarised later (`mutedDropsPending`).
- Two person cameras in the same minute → first pushes, second is counted (`secondClipInTheHourIsCounted`).

---

### Task 1: Uploader reads and stamps the mode

**Files:** Modify `camera-uploader/src/camera_uploader/{hub,cycle,sidecar,heartbeat,main}.py`; tests `camera-uploader/tests/{test_hub,test_cycle,test_sidecar,test_heartbeat}.py`.

**Interfaces:**
- Produces: `async ReolinkHub.mode() -> str | None` (never raises); `build_sidecar(clip, hub_info, uploader_version, tz, mode=None)` adds `"mode"`; `CycleResult.mode: str | None`; `Heartbeat.set_mode(mode)` + payload key `"mode"`, a mode change sends early.

- [ ] **Step 1: Write failing tests:**
  - `test_hub.py::test_mode_reads_active_scene_lower_case` (fake `_host.baichuan` with `get_scene()` and `active_scene == "Away"` → `"away"`), `::test_mode_failure_returns_none` (get_scene raises/timeouts → `None`), `::test_mode_is_not_blocked_by_guard` (after `install_read_only_guard()`, `Baichuan.get_scene` / `get_scene_info` are not wrapped as blocked).
  - `test_sidecar.py::test_sidecar_has_mode` (`mode="away"` → `"mode": "away"`; default `None`).
  - `test_cycle.py::test_clips_stamped_with_mode` (FakeHub `mode()` → `"away"`; uploaded `.json` has `"mode": "away"`; `result.mode == "away"`), `::test_mode_failure_does_not_fail_the_cycle` (FakeHub `mode()` returns `None` → clip uploaded, `"mode": null`).
  - `test_heartbeat.py::test_payload_has_mode_and_change_sends_early`.
- [ ] **Step 2: Run** `cd camera-uploader && .venv/bin/pytest -q` — Expected: the new tests fail.
- [ ] **Step 3: Implement.** `connect()` loads scene names best effort (`get_scene_info` per id ≥ 0, each under a short timeout); `mode()` = `await wait_for(baichuan.get_scene(), SEARCH_TIMEOUT)` then `baichuan.active_scene.lower()`; `UNKNOWN`/exception → `None` (log at warning, once per change). `run_cycle` calls `hub.mode()` once after `info()`, passes it to every `build_sidecar`, sets `result.mode`; `main` calls `heartbeat.set_mode(result.mode)`. Update FakeHubs in `test_cycle.py`/`test_main.py` with `async def mode(self)`.
- [ ] **Step 4: Run** `.venv/bin/pytest -q` — Expected: all pass (159 + new).
- [ ] **Step 5: Commit** `feat(uploader): read the hub's active mode and stamp it into clips and the heartbeat`.

### Task 2: Server pushes person clips recorded in away mode

**Files:** Create `src/main/java/com/davidneto/homepage/cameras/PersonPushNotifier.java`; modify `ClipSidecar.java`, `ClipFiles.java` (read `mode`), `ClipIndexer.java` (hand new clips to the notifier), `Notifier.java`/`NtfyClient.java` (click URL + tags), `StorageStatus.java` + `ClipService.status` (`hubMode` from heartbeat), `AdminCamerasController.java` (health line), `CamerasConfig.java` (schedule), `CameraProperties.java` (`publicUrl`, default `https://davidneto.eu`; `personPushDelay`); tests `PersonPushNotifierTest.java` (new, `MutableClock` from AlertCheckerTest, fake Notifier), `ClipIndexerTest.java`, `ClipFilesTest.java`, `AdminCamerasPageIT.java`, `NtfyClientTest.java`.

**Interfaces:**
- Consumes: sidecar/heartbeat key `"mode"` (Task 1); `AlertMute.get()`; `CameraSettingRepository`.
- Produces: `PersonPushNotifier.clipAdded(ClipSidecar s, Instant indexedAt)`, `PersonPushNotifier.flush()`; `ClipSidecar.mode()` (nullable); `StorageStatus.hubMode()` (nullable); `Notifier.send(String title, String message, String priority, String tags, String clickUrl)`.

- [ ] **Step 1: Write failing tests** (`PersonPushNotifierTest`): `firstAwayPersonClipPushesNow` (title/body/click exact), `homeOrNullModeNeverPushes`, `motionOnlyNeverPushes`, `oldClipsNeverPush` (start 31 min before → none; 29 min → push), `secondClipInTheHourIsCounted` + `summaryAfterTheHour` (flush at +59 min → nothing; at +60 min → `2 more person clips while away` / `Garage 1, Backyard 1`; a clip at +61 min is then counted, not pushed), `noSummaryWhenNothingPending`, `mutedSendsNothingAndDropsPending`, `stateSurvivesRestart` (new notifier on the same repository keeps the last-push time and pending counts), `failedSendIsRetriedByFlush` (send returns false → kept pending). `ClipFilesTest::sidecarModeIsRead` (+ absent → null). `ClipIndexerTest::newClipsAreHandedToThePushNotifier` (only newly added, not existing rows). `AdminCamerasPageIT::healthLineShowsMode` (heartbeat `"mode":"home"` → `mode: home`; no mode → no `mode:`). `NtfyClientTest::clickAndTagsHeaders`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='PersonPushNotifierTest,ClipFilesTest,ClipIndexerTest,AdminCamerasPageIT,NtfyClientTest'` — Expected: compile failure / failures.
- [ ] **Step 3: Implement** to the Global Constraints. Notifier exceptions/false never break indexing.
- [ ] **Step 4: Run** the full suite — Expected: all pass.
- [ ] **Step 5: Commit** `feat(cameras): push person clips recorded in away mode, at most hourly`.

### Task 3: Ship

- [ ] **Step 1:** Fresh whole-branch review; fix Critical/Important with RED→GREEN.
- [ ] **Step 2:** Merge to `main`, push, `DEPLOY_SSH_TARGET=davidneto-vps DEPLOY_REMOTE_REPO=/root/my-homepage scripts/deploy.sh` (never `--full`); health checks OK. Server first: it ignores unknown sidecar keys, so the order is safe either way.
- [ ] **Step 3:** Sync `camera-uploader/` to `macmini:~/camera-uploader-src` (rsync, excluding `.venv`), David runs `ssh -t macmini 'sudo ~/camera-uploader-src/deploy/install.sh'`; verify the next clip's `.json` and `heartbeat.json` carry `mode` (read-only on the VPS) and the page shows `mode: home`.
- [ ] **Step 4:** David switches to away in the Reolink app, walks past a camera → one push; a second walk within the hour → no push, summary after the hour.
