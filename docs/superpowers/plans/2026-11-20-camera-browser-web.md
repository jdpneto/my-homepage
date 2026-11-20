# Camera Browser — Web Page Implementation Plan (plan 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A page at `/admin/cameras` where David browses (all cameras or one camera), plays, keeps, downloads and deletes camera clips, sees storage and uploader health, and switches alerts on/off — fast on a phone browser too.

**Architecture:** Server-rendered Thymeleaf shell in the existing admin style (filters as query parameters, first page of clips grouped by day, storage panel), plus one vanilla JS file for infinite scroll, the full-screen player and the Keep/Delete/Download actions, all calling the plan-1 API (`/api/cameras/**`) with the admin session and the `XSRF-TOKEN` cookie. Backend additions (Task 0): alerts mute setting and per-camera summaries.

**Tech Stack:** Spring Boot 4 MVC + Thymeleaf, existing `terminal.css`, vanilla ES2020 JS (no build step), MockMvc ITs, Chrome (claude-in-chrome) for the in-browser check.

**Spec:** `docs/superpowers/specs/2026-11-18-camera-browser-design.md` (Phase 3 + "API as built")

## Global Constraints

- URL `/admin/cameras`, admin login only (`/admin/**` is `hasRole(ADMIN)`); add "Cameras" to the admin nav fragment.
- Besides Task 0's additions, only the existing API is used: `GET /api/cameras/clips` (cursor `before=<ms>_<id>`, `nextBefore`), `GET clips/{id}/video|thumb|download`, `PUT|DELETE clips/{id}/keep`, `DELETE clips/{id}` (409 when kept), `GET status`, `GET cameras` (`[{name, cameraId}]`), `POST auth/revoke-all`, `POST alerts/test`.
- Writes send header `X-XSRF-TOKEN` with the value of the `XSRF-TOKEN` cookie; the page renders `${_csrf.token}` in a `<meta name="csrf">` so the cookie is always issued.
- Times shown in the clip's own UTC offset (as recorded); day headings "Today", "Yesterday", else `Tue 18 Nov`.
- Disk bar text: `Kept 1.2 GB · Last 30 days 2.1 GB · Free 9.8 GB` (free = quota − used); amber at ≥ 85 % of quota or volume, red on any warning containing "missing".
- Health line: `Uploader: last heartbeat 3 min ago · Hub OK` / `Hub unknown` / `Hub DOWN`; newest clip per camera.
- Delete of a kept clip: no API call; the player says "This clip is kept — unkeep it to delete." Delete of an unkept clip: two-step inline confirm ("Delete?" → "Yes, delete"), never `window.confirm`.
- Page response headers include `X-Robots-Tag: noindex, nofollow` and `<meta name="robots" content="noindex,nofollow">`.
- No new JS/CSS libraries; files `static/js/cameras.js`, `static/css/cameras.css`.
- Commits: no `Co-Authored-By` lines. Java tests: `./mvnw -q test`.

## Review Focus

1. **Camera names with spaces/accents/`&`** ("Living Room", "Cozinha é") in filter links and data attributes → correctly encoded, filter works. Test in Task 1.
2. **Empty states** (no clips at all; filter with no results; volume missing) → friendly message, no JS errors. Test in Task 1 (server) and Task 3 (browser).
3. **Session expired while the page is open** (API returns 401) → the page shows "Signed out — reload to sign in" instead of failing silently. Task 2 JS + Task 3 browser check.
4. **Phone width (375 px)** → list, filters and player usable, no horizontal scroll. Task 3 browser check.
5. **Stale cursor / clip deleted from another device** → next page still loads; a 404 on open shows "Clip no longer exists" and removes the row. Task 2 JS + Task 3.

---

### Task 0: Backend additions — alerts on/off and per-camera summaries

Added after David asked (2026-11-20) to switch notifications on/off and to see videos per camera.

**Files:**
- Create: `src/main/resources/db/migration/V7__camera_settings.sql` (`camera_setting(name varchar(64) primary key, value varchar(500) not null)`), `cameras/CameraSetting.java`, `cameras/CameraSettingRepository.java`, `cameras/AlertMute.java`, `cameras/CameraSummary.java` (record)
- Modify: `cameras/AlertChecker.java`, `cameras/CameraAlertsController.java`, `cameras/ClipService.java`, `cameras/CameraClipRepository.java`, `cameras/CameraApiController.java`
- Test: `cameras/AlertMuteTest.java`, additions to `AlertCheckerTest`, `CameraApiIT`, `ClipServiceTest`

**Interfaces:**
- `AlertMute(CameraSettingRepository, Clock)`: `record MuteState(boolean muted, Instant until)` (until null = until switched back on); `MuteState get()` (an expired `until` reads as not muted); `MuteState set(boolean muted, Instant until)`.
- API: `GET /api/cameras/alerts/mute` → `{"muted":bool,"until":iso|null}`; `PUT /api/cameras/alerts/mute` body `{"muted":true,"until":"2026-11-20T21:00:00+01:00"|null}` → same shape.
- `AlertChecker`: while muted nothing is sent and problems stay tracked; a problem that started while muted is announced at the first check after unmuting (it is still undelivered); a problem that started **and** cleared while muted is closed silently (no "Resolved" for something never announced); "Resolved" for a problem announced before muting is sent after unmuting.
- `record CameraSummary(String name, String cameraId, long clipCount, Long latestClipId, OffsetDateTime latestStart)`; `ClipService.cameraSummaries()`; `GET /api/cameras/cameras` returns these objects (adds fields; `name` and `cameraId` unchanged).

- [ ] **Step 1: Failing tests:** `muteForever`, `muteUntilExpires` (until in the past → not muted), `mutedSendsNothingButKeepsState`, `startedWhileMutedAnnouncedAfterUnmute`, `startedAndClearedWhileMutedIsSilent`, `resolvedAfterUnmuteForProblemAnnouncedBefore`, `muteEndpointRoundTrip` (+ CSRF/auth like other writes), `cameraSummariesHaveCountsAndLatest`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): alerts on/off with optional mute-until; per-camera summaries"`

### Task 1: Server-rendered page (shell, filters, first page, storage)

**Files:**
- Create: `src/main/java/com/davidneto/homepage/cameras/AdminCamerasController.java`, `src/main/java/com/davidneto/homepage/cameras/ClipDayGroup.java` (record)
- Create: `src/main/resources/templates/admin/cameras.html`
- Modify: `src/main/resources/templates/admin/fragments.html` (nav item "Cameras", key `cameras`)
- Test: `src/test/java/com/davidneto/homepage/cameras/AdminCamerasPageIT.java`

**Interfaces:**
- Consumes: `ClipService.list(ClipQuery)`, `ClipService.cameras()`, `ClipService.status()`, `ClipCursor`, `Clock`.
- Produces: `GET /admin/cameras?camera=&trigger=&kept=` → view `admin/cameras` with model `groups: List<ClipDayGroup>`, `cameras: List<CameraInfo>`, `status: StorageStatus`, `filter` (camera, trigger, kept), `nextBefore: String|null`; `record ClipDayGroup(String label, List<ClipView> clips)`; `static List<ClipDayGroup> AdminCamerasController.group(List<ClipView> clips, OffsetDateTime now)`.
- Markup contract for Task 2: each row `<li class="clip" data-id data-start data-camera data-kept>` containing `<img class="thumb" loading="lazy" src="/api/cameras/clips/{id}/thumb">`, time, camera, triggers, duration, ★/☆; list container `#clips` with `data-next` (cursor) and `data-query` (current filter query string); `<template id="clip-row">` for rows added by JS; `#player` overlay markup (hidden); `#storage` panel; buttons `#revoke-all`, `#test-alert`.

- [ ] **Step 1: Write failing tests** (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, files under `app.cameras.root-dir` like `CameraApiIT`, `with(user("admin").roles("ADMIN"))`):
  - `pageRendersGroupsNewestFirst` — 3 clips on 18 Nov and 1 on 16 Nov → two `<h3>` day headings in that order, 4 `li.clip`, first row time `21:49:41`.
  - `groupLabelsTodayYesterdayDate` — unit test of `group(...)` with now = 2026-11-18T22:00+01:00 → labels `Today`, `Yesterday`, `Sun 16 Nov`.
  - `filterLinksEncodeNames` — camera "Living Room" → link `href` contains `camera=Living%20Room`; `GET /admin/cameras?camera=Living%20Room` shows only that camera's rows.
  - `storagePanelShowsFormattedSizes` — status text contains `Kept`, `Last 30 days`, `Free` and a `GB`/`MB` value; heartbeat line contains `Uploader`.
  - `emptyStateMessage` — no clips → text `No clips` and no `li.clip`.
  - `requiresAdmin` — unauthenticated → redirect to `/admin/login`; `X-Robots-Tag: noindex, nofollow` on the 200 response; `<meta name="csrf"` present.
  - `navHasCamerasLink` — `/admin/posts` contains `href="/admin/cameras"`.
  - `cameraCardsLinkToPerCameraView` — one `.camera-card` per camera with its latest thumbnail (`/api/cameras/clips/{latestClipId}/thumb`), clip count and "last clip" time, linking to `/admin/cameras?camera=<encoded name>`; the selected camera's card is marked `aria-current`.
  - `alertsSwitchShowsState` — page shows `Alerts on` / `Alerts muted until 21:00` / `Alerts off` from `AlertMute`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=AdminCamerasPageIT` → FAIL (404 / missing class).
- [ ] **Step 3: Implement** controller, `group`, template (reuse `terminal.css` classes `section-header`, `post-list`, `post-item`; include `cameras.css` and `cameras.js` with `defer`), nav item. Sizes formatted with one decimal: `B`, `KB`, `MB`, `GB` (1024-based). Free = `quotaBytes − quotaUsed` when quota > 0, else `volumeFree`.
- [ ] **Step 4: Run** `./mvnw -q test` → all pass.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): /admin/cameras page with filters, day groups and storage panel"`

---

### Task 2: Player, actions and infinite scroll (JS + CSS)

**Files:** Create `src/main/resources/static/js/cameras.js`, `src/main/resources/static/css/cameras.css`

**Interfaces:**
- Consumes: Task 1 markup contract; plan-1 API.
- Produces (behaviour):
  - **Infinite scroll:** an `IntersectionObserver` on a sentinel after `#clips` fetches `GET /api/cameras/clips?<data-query>&before=<data-next>&limit=50`, appends rows from `#clip-row` (inserting new day headings when the day changes), updates `data-next`; stops when `nextBefore` is null.
  - **Player:** clicking a row opens `#player` full-screen with `<video controls playsinline autoplay src="/api/cameras/clips/{id}/video">`, title `Camera · Tue 18 Nov 21:49:41 · person`, buttons Prev/Next (also ←/→ keys and swipe left/right ≥ 60 px), Keep/Unkeep (★), Download (`/api/cameras/clips/{id}/download`), Delete (two-step confirm; for kept clips shows the kept message instead), Close (Esc). Next/Prev walk the rows in the list (loading the next page when at the end).
  - **Keep/Unkeep:** `PUT`/`DELETE /api/cameras/clips/{id}/keep` with `X-XSRF-TOKEN`; update the row's ★ and `data-kept`.
  - **Delete:** `DELETE /api/cameras/clips/{id}`; on 204 remove the row and move the player to the next clip (or close); on 409 show the kept message.
  - **Errors:** 401 anywhere → banner `Signed out — reload to sign in`; 404 on open → toast `Clip no longer exists` and remove the row; other failures → toast with the status code.
  - **Alerts switch:** `#alerts-toggle` (on/off) and `#alerts-mute-8h` → `PUT /api/cameras/alerts/mute`; label updates in place.
  - **Buttons:** `#revoke-all` → `POST /api/cameras/auth/revoke-all` → toast `Signed out N app devices`; `#test-alert` → `POST /api/cameras/alerts/test` → toast `Test alert sent` / `Not sent — check the ntfy topic`.
  - **CSS:** rows as a responsive grid (thumbnail 160×90 on desktop, full-width list on ≤ 600 px), player covers the viewport with the video `max-height: 75vh`, controls in a bottom bar; no horizontal scroll at 375 px.

- [ ] **Step 1: Write a failing smoke assertion** in `AdminCamerasPageIT`: `staticAssetsServed` — `GET /js/cameras.js` and `/css/cameras.css` → 200 and the JS contains `X-XSRF-TOKEN` and `IntersectionObserver`.
- [ ] **Step 2: Run** → FAIL (404). **Step 3: Implement** both files. **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): web player, keep/delete/download and infinite scroll"`

---

### Task 3: In-browser verification (local) and ship

**Files:** none new (fixes go into Task 1/2 files with their own commits).

- [ ] **Step 1: Run the app locally with real clips:** generate 60 clips with ffmpeg (`testsrc`, 2–5 s) across "Garage", "Living Room", "Front door" over 3 days in the uploader layout with sidecars and the volume marker under a temp `CAMERA_ROOT_DIR`; start `./mvnw spring-boot:run` with the `test` profile (H2) and `app.cameras.root-dir` / `thumbs-dir` pointing there and `app.cameras.index-initial-delay-ms=1000`; wait for the indexer.
- [ ] **Step 2: Drive Chrome** (claude-in-chrome; new tab; record a GIF `camera-web-check.gif`): log in at `/admin/login` (test admin `admin/admin`), open `/admin/cameras`, verify: day groups and thumbnails; camera cards (latest thumbnails) → tap "Living Room" → only its clips; filter "person"; scroll to load page 2; open a clip, play/seek, Next/Prev by keys, Keep (★ appears, `.keep` file on disk), Delete of a kept clip shows the kept message, Unkeep then Delete (row gone, files gone); Download link has a filename; "Send test alert" toast; Alerts off → on, Mute 8 h shows "muted until"; resize to 375 px wide and repeat open/close; delete the session cookie and press Keep → "Signed out" banner. Read the console for errors (none allowed).
- [ ] **Step 3: Fix** anything found, with a failing IT first where the defect is server-side; re-run Step 2 for the affected flow.
- [ ] **Step 4: Ship** — merge to `main`, push, `DEPLOY_SSH_TARGET=davidneto-vps DEPLOY_REMOTE_REPO=/root/my-homepage scripts/deploy.sh`; health checks OK; David opens `https://davidneto.eu/admin/cameras` on his phone and laptop.
