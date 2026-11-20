# Camera Bulk Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let David delete a whole day of clips or a hand-picked selection from `/admin/cameras`, never touching kept clips.

**Architecture:** One new endpoint `POST /api/cameras/clips/delete` deletes an explicit list of IDs through the same per-clip steps as `ClipService.delete` and reports per-ID outcomes. The page gains day-heading Delete buttons and a Select mode, both sending only IDs that were loaded on screen.

**Tech Stack:** Spring Boot 4.1.2 / Java 21, Jackson 3, MockMvc ITs on H2; Thymeleaf; vanilla JS/CSS (`cameras.js`, `cameras.css`).

**Spec:** `docs/superpowers/specs/2026-11-20-camera-bulk-delete-design.md` (addendum to `2026-11-18-camera-browser-design.md`).

## Global Constraints

- A kept clip (`.keep` file on the volume) is never deleted by the bulk path — checked on the server per clip, not only in the page.
- Only IDs the page sends are deleted; no server-side "delete by rule".
- 1–500 IDs per request, duplicates collapsed; empty, > 500, null or non-numeric → 400.
- Response 200 `{"deleted":[ids],"freedBytes":n,"skippedKept":[ids],"notFound":[ids],"failed":[ids]}`.
- Same auth/CSRF as other camera writes (admin session + XSRF, or bearer).
- Confirm copy: "Yes, delete N clips · X MB" / "Yes, delete N · K kept stay"; toast "Deleted N clips · X MB freed" (+ "K kept skipped", "M was/were already gone").
- Double-tap guards as the single delete: `CONFIRM_MIN_MS`, quiet window after acting, no key auto-repeat; 4 s disarm.
- Commits: no co-credit lines (repo CLAUDE.md).

## Review Focus

- A clip kept on another device after the page loaded is in the selection → server skips it, page marks the row kept (`bulkDeleteSkipsKeptByTheFileNotTheRow`).
- A day longer than the loaded pages → Delete day loads the rest of the day before arming, sends all of its non-kept IDs and nothing from the next day (browser check).
- A selection over 500 → the page sends chunks of ≤ 500 and sums the results (browser check with a stubbed endpoint).
- One clip fails on disk mid-batch → others still deleted, that row stays, error toast (`deleteManyContinuesPastAFailedClip`).
- Select mode on a phone → tapping a row ticks it, never opens the player; sticky bar doesn't hide the last row (browser check at 375x812).

---

### Task 1: Bulk delete endpoint

**Files:**
- Create: `src/main/java/com/davidneto/homepage/cameras/BulkDeleteResult.java`
- Modify: `src/main/java/com/davidneto/homepage/cameras/ClipService.java`, `CameraApiController.java`
- Test: `src/test/java/com/davidneto/homepage/cameras/ClipServiceTest.java`, `CameraApiIT.java`

**Interfaces:**
- Produces: `record BulkDeleteResult(List<Long> deleted, long freedBytes, List<Long> skippedKept, List<Long> notFound, List<Long> failed)`; `BulkDeleteResult ClipService.deleteMany(Collection<Long> ids)`; `POST /api/cameras/clips/delete` body `{"ids":[...]}` → 200 BulkDeleteResult JSON.

- [ ] **Step 1: Write failing service tests** in `ClipServiceTest` (use `fiveClips()`):
  - `deleteManyDeletesSkipsKeptAndReportsUnknown`: keep one id; `deleteMany([a, kept, b, 999999, a])` → `deleted` = [a, b] (each once), `skippedKept` = [kept], `notFound` = [999999], `failed` empty, `freedBytes` = sum of a and b `sizeBytes`; a/b mp4 gone, kept mp4 exists.
  - `bulkDeleteSkipsKeptByTheFileNotTheRow`: write the `.keep` file via `ClipFiles.setKept(stem, true)` without updating the row → id lands in `skippedKept`, mp4 exists.
  - `deleteManyContinuesPastAFailedClip`: make `Backyard/2026-11-16` read-only (POSIX `r-x`), delete all five → that clip's id in `failed`, the other four in `deleted`; restore permissions in `finally`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ClipServiceTest` — Expected: compile failure (no `deleteMany`).
- [ ] **Step 3: Implement** `BulkDeleteResult` and `ClipService.deleteMany` — iterate distinct ids in given order; per id: missing row → notFound; `files.isKept(stem)` → skippedKept; else the same steps as `delete` inside try/catch (`RuntimeException` → failed, log WARN with id); sum `sizeBytes` of deleted; one INFO log line with the counts and freed bytes.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=ClipServiceTest` — Expected: PASS.
- [ ] **Step 5: Write failing ITs** in `CameraApiIT`:
  - `bulkDeleteReturnsOutcomes`: two clips, one kept; POST `{"ids":[a,kept,424242]}` with `admin` + `csrf()` → 200, `deleted`=[a], `skippedKept`=[kept], `notFound`=[424242], `freedBytes` > 0.
  - `bulkDeleteValidates`: `{"ids":[]}`, 501 ids, `{}`, `{"ids":["x"]}` → 400 each.
  - `bulkDeleteNeedsCsrfAndAuth`: no csrf → 403; no user → 401.
- [ ] **Step 6: Run** `./mvnw -q test -Dtest=CameraApiIT` — Expected: the three new tests fail (404/405).
- [ ] **Step 7: Implement** `@PostMapping("/clips/delete") BulkDeleteResult deleteMany(@RequestBody BulkDeleteRequest body)` with `record BulkDeleteRequest(List<Long> ids)` nested in the controller; null/empty/> 500 → `ResponseStatusException(BAD_REQUEST)`.
- [ ] **Step 8: Run** full suite — Expected: all pass.
- [ ] **Step 9: Commit** `feat(cameras): bulk delete endpoint (explicit ids, kept clips skipped)`.

### Task 2: Delete a day and Select mode on the page, then ship

**Files:**
- Modify: `src/main/resources/templates/admin/cameras.html`, `static/js/cameras.js`, `static/css/cameras.css`
- Test: `src/test/java/com/davidneto/homepage/cameras/AdminCamerasPageIT.java`

**Interfaces:**
- Consumes: `POST /api/cameras/clips/delete` (Task 1); `GET /api/cameras/status` (`StorageStatus`: `volumeFree`, `keptBytes`, `unkeptBytes`) for the storage line refresh; `ClipView.sizeBytes` in page JSON.
- Produces (markup contract): `li.clip[data-size]`; `#select-toggle` button in the filter row; `h3.day` contains `.day-delete` button; `#select-bar` with `#select-count`, `#select-delete`, `#select-cancel`; `#clips` gets class `selecting` in select mode.

- [ ] **Step 1: Write failing page tests**: `rowsCarrySize` (`data-size="` present with the clip's byte count), `selectModeAndDayDeleteMarkupPresent` (`id="select-toggle"`, `class="day-delete"`, `id="select-bar"`), and in `revokeAllAsksFirst` style `bulkDeleteScriptUsesTheEndpointAndConfirms` (JS contains `/clips/delete` and `Yes, delete`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AdminCamerasPageIT` — Expected: the three fail.
- [ ] **Step 3: Implement** template + JS + CSS per the spec's "Page" section and Global Constraints (heading `<Day> · N clips · X MB` / `N+ clips`; Delete day loads the rest of the day then arms; Select mode toggles rows, kept rows not tickable, per-day "select whole day", Escape/Cancel exits, chunks of 500; after delete: remove deleted+notFound rows, mark skippedKept kept, keep failed rows + error toast, update headings and empty state, refresh storage line; 401/403 → signed-out banner).
- [ ] **Step 4: Run** `node --check src/main/resources/static/js/cameras.js` and `./mvnw -q test -Dtest=AdminCamerasPageIT` — Expected: OK, PASS.
- [ ] **Step 5: Browser check** on a local run (Postgres container + generated clips, as in plan 2 Task 3) of every Review Focus line plus: day delete with a camera filter only deletes that camera; double-tap on "Yes, delete" sends one request; regression of player open/keep/single delete; 375x812 and 1440x900; no console exceptions. Fix, re-check.
- [ ] **Step 6: Commit** `feat(cameras): delete a day and select mode`, run full suite.
- [ ] **Step 7: Ship** — merge to `main`, push, `DEPLOY_SSH_TARGET=davidneto-vps DEPLOY_REMOTE_REPO=/root/my-homepage scripts/deploy.sh` (never `--full`); health checks OK; `/admin/cameras` unauth → 302.
