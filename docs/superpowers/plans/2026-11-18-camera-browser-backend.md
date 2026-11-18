# Camera Browser — Backend Implementation Plan (plan 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect the camera recordings, and give the homepage app a clip index, an authenticated JSON API, a 30-day cleanup that never touches kept clips, disk/uploader status and ntfy alerts — plus the uploader heartbeat they depend on.

**Architecture:** New package `com.davidneto.homepage.cameras` in the Spring Boot app. Files on the volume are the source of truth (clips, sidecars, `.keep` markers); Postgres caches an index rebuilt by a 30 s reconcile job. A new security chain for `/api/cameras/**` accepts either the admin session (web page, plan 2) or a bearer token (apps, plan 3). Alerts go to ntfy over HTTP. The Mac mini uploader gains a `heartbeat.json` PUT.

**Tech Stack:** Spring Boot 4.1.2 / Java 21, Spring Security, Spring Data JPA, Flyway, Postgres 16 (H2 in PostgreSQL mode for tests), Jackson, ffmpeg; Python 3.13 for the uploader change.

**Spec:** `docs/superpowers/specs/2026-11-18-camera-browser-design.md` (and part 1: `2026-11-18-camera-uploader-design.md`)

**Later plans:** plan 2 = web page `/admin/cameras` (Thymeleaf), plan 3 = Flutter app — both consume this plan's API.

## Global Constraints

- Clips: `<root>/<Camera>/<YYYY-MM-DD>/<HH-MM-SS>[-N].mp4` + same-stem `.json`; `<root>` = `app.cameras.root-dir` (container: `/app/webdav/cameras`). A clip exists for the app only when its `.json` exists and parses.
- Keep marker: zero-byte `<stem>.keep` next to the clip — the **source of truth**; DB `kept` is a cache.
- Never delete, rename or list as clips: `volume-marker.txt`, `heartbeat.json`, `*.keep`.
- Volume guard: indexer, cleanup and status treat the volume as **missing** unless `<root>/volume-marker.txt` exists and starts with `camera clips live on the 15 GB volume`; when missing, indexer and cleanup do nothing.
- Protected WebDAV user = its root contains `volume-marker.txt`. For protected users: admin delete / clear data / self-service clear refuse; WebDAV `DELETE` and `MOVE` → 403; `PUT` and `MKCOL` allowed.
- Retention: 30 days by **recording start** from the sidecar; skip if `<stem>.keep` exists **on disk at deletion time**. Daily `0 30 3 * * *`.
- Thumbnails: JPEG, 480 px wide, in `app.cameras.thumbs-dir` (container `/app/camera-thumbs`, new Docker volume `camera_thumbs`), never under `<root>`.
- Tokens: 256-bit random, base64url; only SHA-256 hashes stored; valid 90 days, sliding on use; `POST /api/cameras/auth/token` rate-limited with the existing `LoginRateLimiter`.
- Every `/api/cameras/**` response: authenticated (except `POST auth/token`), header `X-Robots-Tag: noindex, nofollow`.
- Alerts: ntfy `POST {CAMERA_NTFY_URL}/{CAMERA_NTFY_TOPIC}` text body, `Title` header; one on start, one on resolve, reminder every 24 h. Conditions: heartbeat older than 60 min; heartbeat `hub_ok=false` for ≥ 60 min; volume or quota ≥ 85 %; root disk ≥ 90 %; cleanup failed. Checked every 5 min. Text only.
- Heartbeat: uploader PUTs `heartbeat.json` (at most every 300 s) with `{"time","uploader_version","hub_ok","hub_error_since","cameras","last_error"}`; times ISO 8601 with offset.
- Single user: the admin from `app.admin.username`/`app.admin.password`.
- Commits: no `Co-Authored-By` lines (repo CLAUDE.md). Java tests: `./mvnw -q test` (baseline 137 passing). Uploader tests: `cd camera-uploader && .venv/bin/pytest -q`.

## Review Focus

1. **Clip half-written when the indexer runs** (`.mp4` present, `.json` missing or truncated) → skipped silently, indexed on a later pass. Test in Task 6.
2. **Camera names with spaces/accents** ("Living Room", "Cozinha é") → API uses numeric ids, filters by exact name, JSON returns the name intact. Test in Task 9.
3. **iPhone player range probes** (`Range: bytes=0-1`, open-ended `bytes=100-`) → 206 with correct `Content-Range`. Test in Task 9.
4. **Volume detached** (`<root>` exists but empty) → indexer keeps rows, cleanup deletes nothing, status reports `volumeOk=false`, alert fires. Tests in Tasks 6, 7, 10.
5. **Keep toggled while the cleanup runs / unkeep of an old clip** → cleanup re-checks `.keep` on disk per clip; an old unkept clip is removed on the next run. Test in Task 7.

---

### Task 1: Protected WebDAV folders

**Files:**
- Modify: `src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java` (add `isProtected`)
- Modify: `src/main/java/com/davidneto/homepage/service/WebDavUserService.java` (`delete`, `clearData`, `clearDataByUsername`)
- Modify: `src/main/java/com/davidneto/homepage/controller/WebDavUserAdminController.java` (pass `protected` per row; flash error on refusal)
- Modify: `src/main/resources/templates/admin/webdav-users.html` (protected badge; hide clear/delete)
- Create: `src/main/java/com/davidneto/homepage/webdav/WebDavProtectionFilter.java`, `src/main/java/com/davidneto/homepage/webdav/BasicAuthUsername.java`
- Modify: `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java` (register filter), `WebDavQuotaFilter.java` (use `BasicAuthUsername`)
- Test: `src/test/java/com/davidneto/homepage/webdav/ProtectedFolderIT.java`

**Interfaces:**
- Produces: `boolean PerUserFileResourceFactory.isProtected(String username)` (true when `<webdav-root>/<username>/volume-marker.txt` is a regular file); `class ProtectedFolderException extends IllegalStateException` (in `service`); `static String BasicAuthUsername.parse(String authorizationHeader)` (null when absent/malformed).

- [ ] **Step 1: Write failing tests** in `ProtectedFolderIT` (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`): create users `cameras` and `plain` via `WebDavUserService.create(...)`, write `volume-marker.txt` + `Garage/2026-11-18/21-45-49.mp4` under `cameras`' root.
  - `deleteRefusesProtectedUser`: `assertThrows(ProtectedFolderException.class, () -> service.delete(camerasId))`; marker and clip still exist; user still in repo.
  - `clearDataRefusesProtectedUser`, `selfServiceClearRefusesProtectedUser` (`POST /api/webdav/clear-data` with cameras' Basic auth → 403, files remain).
  - `adminDeleteOfProtectedShowsErrorNotWipe`: `POST /admin/webdav-users/{id}/delete` as admin → redirect with flash error; files remain.
  - `webdavDeleteAndMoveForbiddenForProtected`: `DELETE /webdav/Garage/2026-11-18/21-45-49.mp4` and `MOVE` with `Destination` → 403; file remains.
  - `webdavPutStillAllowedForProtected`: `PUT /webdav/heartbeat.json` → 201.
  - `plainUserUnaffected`: `delete(plainId)` succeeds and wipes `plain`'s folder; `DELETE` over WebDAV as `plain` is not 403.
  - `adminPageMarksProtected`: `GET /admin/webdav-users` contains `protected` next to `cameras` and no delete form for its id.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ProtectedFolderIT` → FAIL (missing classes).
- [ ] **Step 3: Implement.** Service methods check `storage.isProtected(username)` first and throw `ProtectedFolderException("…is on the camera volume and is protected")`. `WebDavProtectionFilter` (`OncePerRequestFilter`, URL pattern `/webdav/*`, order `-1`, before the quota filter): for `DELETE`/`MOVE`, parse the Basic username; if `isProtected` → 403 text `protected folder: deleting and moving are disabled`. Self-service controller maps `ProtectedFolderException` → 403. Extract `parseBasicAuthUsername` from `WebDavQuotaFilter` into `BasicAuthUsername.parse`.
- [ ] **Step 4: Run** `./mvnw -q test` → all pass (137 + new).
- [ ] **Step 5: Commit** `git commit -m "feat(webdav): protect folders on the camera volume from wipes, DELETE and MOVE"`

---

### Task 2: `deploy.sh --full` never endangers the cameras

**Files:** Modify `scripts/deploy.sh`

- [ ] **Step 1:** Move the `checking camera clip volume` block to run before the `if [ "$MODE" = "full" ]` wipe block.
- [ ] **Step 2:** In the `--full` confirmation text add: `Camera recordings on /mnt/HC_Volume_107526857 are NOT touched.`; in the post-`--full` next steps add: `4. Recreate WebDAV user "cameras" with the SAME password and quota 14336 MB (the Mac mini uploader keeps its config).`
- [ ] **Step 3: Verify** `bash -n scripts/deploy.sh && grep -n "checking camera clip volume\|MODE\" = \"full\"" scripts/deploy.sh` → guard line number < wipe line number.
- [ ] **Step 4: Commit** `git commit -m "fix(deploy): check the camera volume before --full wipes; say recordings are untouched"`

---

### Task 3: Schema, properties and files helper

**Files:**
- Create: `src/main/resources/db/migration/V6__add_cameras.sql`
- Create (package `com.davidneto.homepage.cameras`): `CameraProperties.java`, `CameraClip.java` (entity), `CameraClipRepository.java`, `CameraToken.java`, `CameraTokenRepository.java`, `AlertState.java`, `AlertStateRepository.java`, `ClipFiles.java`, `ClipSidecar.java` (record)
- Modify: `src/main/resources/application.yml`, `src/test/resources/application-test.yml`, `docker-compose.yml`
- Test: `src/test/java/com/davidneto/homepage/cameras/ClipFilesTest.java`, `CameraRepositoriesIT.java`

**Interfaces:**
- Produces:
  - Tables: `camera_clip(id bigserial pk, stem varchar(512) unique not null, camera_name, camera_id, channel int, start_at timestamptz, end_at timestamptz, duration_seconds int, triggers varchar(255) /* comma-separated, lower-case, sorted */, size_bytes bigint, kept boolean not null default false, kept_at timestamptz, thumb_path varchar(512), indexed_at timestamptz not null)` with index on `(start_at desc)`, `(camera_name, start_at desc)`; `camera_token(id bigserial pk, token_hash char(64) unique not null, label varchar(100), created_at, last_used_at, expires_at timestamptz not null)`; `camera_alert_state(alert_key varchar(64) pk, active boolean not null, since timestamptz, last_notified_at timestamptz, detail varchar(500))`.
  - `@ConfigurationProperties("app.cameras") record CameraProperties(Path rootDir, Path thumbsDir, int retentionDays /*30*/, String ntfyUrl /*https://ntfy.sh*/, String ntfyTopic /*blank = alerts disabled*/, int warnPercent /*85*/, int rootDiskWarnPercent /*90*/, Duration heartbeatMaxAge /*60m*/, Duration hubDownMaxAge /*60m*/, Duration reminderEvery /*24h*/, String webdavUser /*cameras*/)`.
  - `record ClipSidecar(String cameraName, String cameraId, int channel, OffsetDateTime start, OffsetDateTime end, int durationSeconds, List<String> triggers)` (Jackson, snake_case, unknown fields ignored).
  - `ClipFiles(CameraProperties)`: `boolean volumeOk()`; `List<String> listStems()` (stems with a `.mp4`, layout-matching only); `Optional<ClipSidecar> readSidecar(String stem)` (empty if missing/unparseable); `Path mp4(String stem)`; `boolean isKept(String stem)`; `void setKept(String stem, boolean kept)`; `void deleteClip(String stem)` (mp4, json, keep; refuses names outside the layout); `Optional<JsonNode> readHeartbeat()`.
- Env/compose: `app.cameras.root-dir=${CAMERA_ROOT_DIR:./cameras}`, `thumbs-dir=${CAMERA_THUMBS_DIR:./camera-thumbs}`, `ntfy-url=${CAMERA_NTFY_URL:https://ntfy.sh}`, `ntfy-topic=${CAMERA_NTFY_TOPIC:}`; compose `app.environment`: `CAMERA_ROOT_DIR=/app/webdav/cameras`, `CAMERA_THUMBS_DIR=/app/camera-thumbs`, `CAMERA_NTFY_URL=${CAMERA_NTFY_URL:-https://ntfy.sh}`, `CAMERA_NTFY_TOPIC=${CAMERA_NTFY_TOPIC:-}`; volume `camera_thumbs:/app/camera-thumbs`. Test profile: `./build/test-cameras`, `./build/test-camera-thumbs`.

- [ ] **Step 1: Write failing tests.** `ClipFilesTest` (JUnit `@TempDir`): `volumeOkRequiresMarkerText` (absent → false; wrong text → false; correct → true); `listStemsMatchesLayoutOnly` (finds `Garage/2026-11-18/21-45-49` and `Living Room/2026-11-18/21-45-49-2`; ignores `volume-marker.txt`, `heartbeat.json`, `x.mp4` at root, `Garage/notadate/1.mp4`); `readSidecarParsesUploaderJson` (exact JSON from spec §3 → fields equal, `triggers == ["person"]`, start offset `+01:00`); `readSidecarEmptyWhenTruncated` (`{"camera_name": "Gar`) ; `setKeptCreatesAndRemovesMarker`; `deleteClipRemovesMp4JsonKeep`; `deleteClipRejectsPathTraversal` (`../x` → `IllegalArgumentException`). `CameraRepositoriesIT`: save + find a `CameraClip` by stem; `findByTokenHash`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='ClipFilesTest,CameraRepositoriesIT'` → FAIL.
- [ ] **Step 3: Implement** the migration, entities, repositories, properties (enable via `@EnableConfigurationProperties(CameraProperties.class)` on a new `CamerasConfig`), `ClipFiles`, `ClipSidecar`, config and compose entries. Layout regex for stems: `^[^/]+/\d{4}-\d{2}-\d{2}/\d{2}-\d{2}-\d{2}(-\d+)?$`.
- [ ] **Step 4: Run** `./mvnw -q test` → all pass.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): schema, properties and clip file helpers"`

---

### Task 4: Thumbnails

**Files:** Create `cameras/ClipThumbnailer.java`; Test `cameras/ClipThumbnailerTest.java`

**Interfaces:**
- Produces: `interface FrameExtractor { void extract(Path mp4, Path jpg) throws IOException; }` with default `FfmpegFrameExtractor` (`ffmpeg -y -ss 1 -i <mp4> -frames:v 1 -vf scale=480:-2 -q:v 4 <jpg>`, falling back to no `-ss` when the output is missing/empty — very short clips); `ClipThumbnailer(CameraProperties, FrameExtractor)`: `Optional<Path> ensure(String stem)` (path `<thumbs>/<sha1(stem)>.jpg`; creates if absent; empty on failure, logged at WARN once per stem), `void delete(String stem)`.

- [ ] **Step 1: Write failing tests:** `ensureCreatesOnceAndReuses` (fake extractor counts calls: 1 after two `ensure`); `ensureEmptyOnExtractorFailure`; `deleteRemovesFile`; `realFfmpegMakesJpeg` (skipped unless `ffmpeg` on PATH: generate 2 s `testsrc` mp4, assert JPEG magic bytes and width 480 via `ffprobe`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ClipThumbnailerTest` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): clip thumbnails"`

---

### Task 5: Indexer

**Files:** Create `cameras/ClipIndexer.java`; Test `cameras/ClipIndexerTest.java` (`@DataJpaTest`-style or `@SpringBootTest` with `@TempDir`-backed properties)

**Interfaces:**
- Consumes: `ClipFiles`, `ClipThumbnailer`, `CameraClipRepository`.
- Produces: `record IndexResult(int added, int removed, int keptChanged, boolean volumeOk)`; `IndexResult ClipIndexer.reconcile()`; scheduled `@Scheduled(fixedDelay = 30000, initialDelay = 5000)`.

Behaviour: if `!volumeOk()` → log WARN (once per hour max) and return `volumeOk=false` without touching rows. Else: for stems on disk without a row and with a parseable sidecar → insert (size from mp4, triggers joined, `kept` from marker) and `thumbnailer.ensure`; rows whose mp4 is gone → delete row + thumbnail; rows whose `kept` differs from the marker → update (`kept_at` = now when becoming kept).

- [ ] **Step 1: Write failing tests:** `addsClipWithSidecar`; `skipsMp4WithoutSidecarThenAddsWhenItAppears` (Review Focus 1); `skipsTruncatedSidecar`; `removesRowWhenMp4Deleted`; `picksUpKeepMarkerChanges`; `volumeMissingTouchesNothing` (Review Focus 4: rows exist, marker removed, all mp4s removed → `reconcile()` returns `volumeOk=false`, row count unchanged); `ignoresMarkerHeartbeatAndKeepFiles`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=ClipIndexerTest` → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): reconcile the clip index from the volume"`

---

### Task 6: Clip service (list, keep, delete, status)

**Files:** Create `cameras/ClipService.java`, `cameras/ClipQuery.java` (record), `cameras/ClipView.java` (record), `cameras/StorageStatus.java` (record); Test `cameras/ClipServiceTest.java`

**Interfaces:**
- Consumes: Task 3–5 types, `WebDavUserRepository` (quota of `app.cameras.webdav-user`), `PerUserFileResourceFactory.currentUsageBytes`.
- Produces:
  - `record ClipQuery(String camera, String trigger, OffsetDateTime from, OffsetDateTime to, Boolean kept, Long beforeId, int limit /*1..200, default 50*/)`.
  - `record ClipView(long id, String camera, String cameraId, OffsetDateTime start, OffsetDateTime end, int durationSeconds, List<String> triggers, long sizeBytes, boolean kept)`.
  - `record StorageStatus(boolean volumeOk, long volumeTotal, long volumeFree, long quotaBytes, long quotaUsed, long keptBytes, long unkeptBytes, long rootTotal, long rootFree, Map<String, OffsetDateTime> newestByCamera, OffsetDateTime heartbeatAt, Boolean hubOk, List<String> warnings)`.
  - `ClipService`: `List<ClipView> list(ClipQuery q)` (newest first by `start_at desc, id desc`; `beforeId` = keyset cursor); `Optional<ClipView> get(long id)`; `ClipView keep(long id, boolean kept)` (writes/removes marker **then** updates row); `void delete(long id)` (throws `ClipKeptException` if marker exists; else `ClipFiles.deleteClip` + thumbnail + row); `Path videoPath(long id)`; `Optional<Path> thumbPath(long id)`; `List<String> cameras()` (distinct names, sorted); `StorageStatus status()` (volume via `Files.getFileStore(root)`, root disk via `getFileStore(Path.of("/"))`; warnings: `volume ≥ warnPercent`, `quota ≥ warnPercent`, `root ≥ rootDiskWarnPercent`, `volume missing`).

- [ ] **Step 1: Write failing tests:** `listNewestFirstWithKeysetPaging` (5 clips, limit 2, follow `beforeId` → all 5, no dupes); `filtersByCameraWithSpaceTriggerKeptAndRange` ("Living Room", Review Focus 2); `keepWritesMarkerAndRow`; `deleteRefusesKept`; `deleteRemovesFilesThumbAndRow`; `statusComputesKeptVsUnkeptAndWarnings` (fake sizes; quota 14336 MB; usage ≥ 85 % → warning text contains `quota`); `statusVolumeMissing` (`volumeOk=false`, warning `volume missing`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): clip service with keep, delete and storage status"`

---

### Task 7: 30-day cleanup

**Files:** Create `cameras/RetentionJob.java`; Test `cameras/RetentionJobTest.java`

**Interfaces:**
- Consumes: `ClipFiles`, `CameraClipRepository`, `ClipThumbnailer`, `ClipIndexer`, `Clock` (bean; use the app's existing `Clock` bean if present, else add one in `CamerasConfig`).
- Produces: `record RetentionResult(int deleted, int skippedKept, boolean ran, String reason)`; `RetentionResult RetentionJob.run()`; `@Scheduled(cron = "0 30 3 * * *")` wrapper that catches any exception; `Optional<String> RetentionJob.lastFailure()` — the error text of the most recent scheduled run if it threw (empty after a successful run; in memory only), consumed by Task 10's `cleanup_failed` alert.

Behaviour: run `indexer.reconcile()` first; if `!volumeOk` → `ran=false, reason="volume missing"`; if the index is empty but `listStems()` is not → `ran=false, reason="index empty"`. Candidates: rows with `start_at < now - retentionDays`. For each: if `isKept(stem)` on disk → skip (and fix the cached flag); else `deleteClip` + thumbnail + row. One INFO summary line.

- [ ] **Step 1: Write failing tests:** `deletesOnlyOlderThan30Days` (clips at 31 d and 29 d); `neverDeletesKeptEvenIfRowSaysNotKept` (marker on disk, row `kept=false` — Review Focus 5); `unkeptOldClipDeletedOnNextRun`; `volumeMissingDeletesNothing`; `emptyIndexWithFilesDeletesNothing`; `neverTouchesMarkerOrHeartbeat`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): daily 30-day cleanup that never touches kept clips"`

---

### Task 8: Token auth and the `/api/cameras` security chain

**Files:**
- Create: `cameras/CameraTokenService.java`, `cameras/BearerTokenFilter.java`, `cameras/CameraSecurityConfig.java`, `cameras/CameraAuthController.java`
- Test: `cameras/CameraAuthIT.java`

**Interfaces:**
- Produces: `CameraTokenService`: `String issue(String label)` (returns raw token; stores SHA-256 hex, `expires_at = now + 90d`); `Optional<Authentication> authenticate(String raw)` (valid & unexpired → `UsernamePasswordAuthenticationToken(adminUsername, null, ROLE_ADMIN)`; slides `expires_at` to now+90d and sets `last_used_at` at most once per hour); `void revoke(String raw)`; `int revokeAll()`.
- Endpoints: `POST /api/cameras/auth/token` (JSON `{"username","password","label"}` → 200 `{"token","expiresAt"}`; 401 bad credentials; 429 with `Retry-After` from `LoginRateLimiter.check(ip, username)`; records failures/successes on the limiter); `DELETE /api/cameras/auth/token` (revokes the presented token, 204); `POST /api/cameras/auth/revoke-all` (session or token, 200 `{"revoked":n}`).
- Chain: `@Order(1) securityMatcher("/api/cameras/**")`; `permitAll` only `POST /api/cameras/auth/token`; everything else `hasRole("ADMIN")`; `BearerTokenFilter` before `AnonymousAuthenticationFilter`; session security context still honoured (admin logged into `/admin` works); CSRF: `CookieCsrfTokenRepository.withHttpOnlyFalse()` like the admin chain, ignored when the request carries `Authorization: Bearer` and for `POST /api/cameras/auth/token`; unauthenticated → 401 JSON (no redirect); header writer adds `X-Robots-Tag: noindex, nofollow`.

- [ ] **Step 1: Write failing tests:** `tokenLoginWithAdminCredentials` (200, token length ≥ 43); `tokenLoginWrongPassword401`; `tokenLoginRateLimited429AfterFailures` (use the test profile's rate-limit settings); `bearerTokenGrantsAccess` (`GET /api/cameras/cameras` 200); `noAuth401NotRedirect`; `adminSessionGrantsAccess` (`with(user("admin").roles("ADMIN"))` → 200); `sessionPostWithoutCsrf403`, `bearerPostWithoutCsrfAllowed`; `revokedToken401`; `expiredToken401` (set `expires_at` in the past); `tokenStoredHashedOnly` (raw token never equals any `token_hash`); `robotsHeaderPresent`; `existingAdminAndMaeChainsUnaffected` (`/admin/posts` unauth → redirect to `/admin/login`; `/mae` unauth → redirect to `/mae/login`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=CameraAuthIT` → FAIL. **Step 3: Implement** (a stub `GET /api/cameras/cameras` from Task 9 may be added here to make the auth tests meaningful). **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): bearer tokens and the /api/cameras security chain"`

---

### Task 9: Clip API

**Files:** Create `cameras/CameraApiController.java`; Test `cameras/CameraApiIT.java`

**Interfaces:**
- Consumes: `ClipService`, `ClipThumbnailer`.
- Produces (all JSON unless noted, ISO 8601 with offset):
  - `GET /api/cameras/clips?camera=&trigger=&from=&to=&kept=&before=&limit=` → `{"clips":[ClipView…],"nextBefore":<id|null>}`.
  - `GET /api/cameras/clips/{id}` → `ClipView` | 404.
  - `GET /api/cameras/clips/{id}/video` → `video/mp4`, byte ranges (return `ResponseEntity<Resource>` with `FileSystemResource`; Spring answers ranges with 206), `Cache-Control: private, max-age=86400`.
  - `GET /api/cameras/clips/{id}/download` → same file with `Content-Disposition: attachment; filename="<Camera>_<YYYY-MM-DD>_<HH-MM-SS>.mp4"` (camera name sanitised to ASCII-safe, RFC 5987 `filename*` with UTF-8).
  - `GET /api/cameras/clips/{id}/thumb` → `image/jpeg` | 404 when no thumbnail.
  - `PUT /api/cameras/clips/{id}/keep` / `DELETE …/keep` → `ClipView`.
  - `DELETE /api/cameras/clips/{id}` → 204 | 409 `{"error":"clip is kept - unkeep it first"}`.
  - `GET /api/cameras/cameras` → `["Backyard","Front door","Garage","Living Room"]`.
  - `GET /api/cameras/status` → `StorageStatus`.

- [ ] **Step 1: Write failing tests:** `listReturnsNewestFirstAndCursor`; `filterByCameraWithSpace` (`camera=Living%20Room`); `videoFullAndRanges` (no Range → 200 + `Accept-Ranges: bytes`; `bytes=0-1` → 206, body 2 bytes, `Content-Range: bytes 0-1/<size>`; `bytes=100-` → 206 — Review Focus 3); `downloadHasAttachmentFilename`; `thumbOr404`; `keepUnkeepRoundTrip` (marker file appears/disappears); `deleteKept409ThenUnkeepThenDelete204`; `unknownId404`; `statusShape`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): clip API with range streaming, keep and delete"`

---

### Task 10: ntfy alerts

**Files:** Create `cameras/NtfyClient.java`, `cameras/AlertChecker.java`, `cameras/CameraAlertsController.java`; Test `cameras/AlertCheckerTest.java`, `cameras/NtfyClientTest.java`

**Interfaces:**
- Consumes: `ClipService.status()`, `ClipFiles.readHeartbeat()`, `RetentionJob.lastFailure()`, `AlertStateRepository`, `Clock`, `CameraProperties`.
- Produces: `interface Notifier { void send(String title, String message, String priority) }`; `NtfyClient implements Notifier` (`RestClient` POST to `{ntfyUrl}/{ntfyTopic}`, headers `Title`, `Priority` (`high` for problems, `default` for resolved), `Tags`; no-op + one INFO log when topic blank; 10 s timeout; failures logged, never thrown); `AlertChecker.check()` `@Scheduled(fixedDelay = 300000, initialDelay = 60000)`; `POST /api/cameras/alerts/test` → sends `Test alert from davidneto.eu`, 200 `{"sent":true|false}`.
- Alert keys and texts (titles): `uploader_silent` "Camera uploader silent" (heartbeat missing or older than 60 min; detail: last heartbeat time); `hub_down` "Camera hub unreachable" (`hub_ok=false` and `hub_error_since` ≥ 60 min ago); `disk_low` "Camera storage almost full" (any of the `StorageStatus.warnings` about volume/quota/root, detail lists them); `volume_missing` "Camera volume missing"; `cleanup_failed` "Camera cleanup failed" (while `RetentionJob.lastFailure()` is present).
- State machine per key: inactive→active ⇒ send problem, store `since`, `last_notified_at`; active & still true & `now - last_notified_at ≥ 24h` ⇒ send reminder; active→inactive ⇒ send `Resolved: <title>` (priority default).

- [ ] **Step 1: Write failing tests** (fixed `Clock`, fake `Notifier` recording calls): `silentAfter60MinutesSendsOnce` (heartbeat 61 min old → 1 send; check again → 0); `reminderAfter24h`; `resolvedWhenHeartbeatReturns`; `hubDownOnlyAfter60Min` (59 min → none; 61 → send); `diskLowFromWarnings`; `noTopicNoCrash` (`NtfyClient` with blank topic sends nothing, no exception); `ntfyClientPostsTitleAndBody` (`MockRestServiceServer`: POST `https://ntfy.sh/topic123`, header `Title`, body text).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** `./mvnw -q test` → PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(cameras): ntfy alerts for silent uploader, hub down, low disk, cleanup failure"`

---

### Task 11: Uploader heartbeat (Mac mini)

**Files:** Create `camera-uploader/src/camera_uploader/heartbeat.py`; Modify `camera-uploader/src/camera_uploader/main.py`; Test `camera-uploader/tests/test_heartbeat.py`

**Interfaces:**
- Consumes: `WebDavClient.put_bytes`, `HubError`, `__version__`.
- Produces: `class Heartbeat(interval_seconds: float = 300)`: `record(hub_ok: bool, error: str | None, cameras: int | None, now: datetime) -> None`; `async maybe_send(dav, now: datetime) -> bool` (PUTs `heartbeat.json` if ≥ interval since last send or state changed `hub_ok`; returns sent; swallows `WebDavError` with a WARNING); `payload(now) -> dict` with keys `time, uploader_version, hub_ok, hub_error_since, cameras, last_error` (`hub_error_since` = first failure time while `hub_ok` stays false, else null). `run_forever` calls `record` after every cycle (success → `hub_ok=True`; `HubError`/`HubAuthError` → `False`; other errors keep last hub state, set `last_error`) and then `maybe_send`.

- [ ] **Step 1: Write failing tests:** `test_first_send_immediately`; `test_rate_limited_to_interval`; `test_state_change_sends_early`; `test_hub_error_since_kept_across_failures_and_cleared_on_success`; `test_payload_has_no_password` (cfg passwords absent from JSON); `test_run_forever_sends_heartbeat_when_hub_down` (fake hub raising `HubError`, FakeDav records PUT `heartbeat.json` with `hub_ok=false`).
- [ ] **Step 2: Run** `cd camera-uploader && .venv/bin/pytest -q tests/test_heartbeat.py` → FAIL. **Step 3: Implement.** **Step 4: Run** `.venv/bin/pytest -q` → PASS (141 + new).
- [ ] **Step 5: Commit** `git commit -m "feat(camera-uploader): heartbeat.json for server-side alerts"`

---

### Task 12: Ship it

- [ ] **Step 1: ntfy topic** — generate `cam-$(openssl rand -hex 12)` and append `CAMERA_NTFY_TOPIC=<topic>` to `/root/my-homepage/.env` on the VPS over SSH (`davidneto-vps`) without echoing other variables; print the topic once for David.
- [ ] **Step 2: Deploy** — `git push origin main && DEPLOY_SSH_TARGET=davidneto-vps DEPLOY_REMOTE_REPO=/root/my-homepage scripts/deploy.sh` → all health checks OK; `docker compose exec -T app ls /app/camera-thumbs` works.
- [ ] **Step 3: Server smoke (on the VPS, admin password read from `.env` inside the command, never printed):** token login → 200; `GET /api/cameras/clips?limit=3` returns the newest Garage clips; `GET …/video` with `Range: bytes=0-1` → 206; `GET /api/cameras/status` → `volumeOk:true`; thumbnails exist for indexed clips.
- [ ] **Step 4: Uploader** — `rsync` `camera-uploader/` to `macmini:~/camera-uploader-src/`; David runs `ssh -t macmini 'sudo ~/camera-uploader-src/deploy/install.sh'` (sudo password only; config is kept). Then `heartbeat.json` appears on the volume within 5 min and `status.heartbeatAt` is recent.
- [ ] **Step 5: Alerts** — David subscribes to the topic in the ntfy app on both phones; `POST /api/cameras/alerts/test` → push received.
- [ ] **Step 6: Protection check** — admin page shows `cameras` as protected without delete/clear; `curl -X DELETE` on a clip over WebDAV with the `cameras` login → 403 (David runs it, password stays out of the transcript).
