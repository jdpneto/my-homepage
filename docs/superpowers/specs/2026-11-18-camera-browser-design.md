# Camera Clip Browser — Design (Part 2 of 2)

**Status:** approved by David 2026-11-18
**Date:** 2026-11-18
**Builds on:** `2026-11-18-camera-uploader-design.md` (part 1, live: clips arrive on the VPS ~30 s after they end)

## 1. Intent

David now has every Reolink motion clip copied to the VPS. He wants to **look at them from anywhere** — a web page and real phone apps (iPhone and Android) — **keep** the ones that matter forever, **save** clips to his phone, and **see how much space is left**, while the server cleans up everything else after 30 days. The recordings must be impossible to wipe by accident.

Single user: David. He is not distributing the apps; he installs them on his own phones from this machine (he has Apple and Google developer accounts).

## 2. Goals, non-goals, anti-goals

**Goals**
- Browse clips newest-first, grouped by day, filterable by camera, trigger (person/motion/…), date and "kept".
- Play a clip full-screen with instant seeking; swipe/step to previous/next clip.
- **Keep** (star) a clip: never removed by the cleanup. **Unkeep** makes it eligible again.
- **Save to phone** (Photos on iPhone, Gallery on Android) and **share** from the apps; **download** from the web page.
- **Delete** a single clip by hand.
- **Disk view:** space used by kept clips vs. the rolling 30 days, free space on the 15 GB volume and the `cameras` quota, plus the VPS root disk; a visible warning when space runs low.
- **Uploader health at a glance:** time of the newest clip per camera.
- Daily **30-day cleanup** of non-kept clips.
- **Protect the recordings** from the admin "Delete"/"Clear data" buttons, the WebDAV self-service wipe and `deploy.sh --full` (agreed with David before this doc).
- Feels fast on the phones: smooth scrolling, thumbnails, native video player (David's stated reason for real apps).

**Non-goals (v1)**
- Live view, two-way audio, arming/disarming — the Reolink app does these; live view would also wake the battery cameras.
- Motion push notifications — the Reolink app already notifies. (Operational alerts — disk, uploader, hub — are in scope via ntfy, Phase 2b.)
- Other users, sharing links, roles.
- App Store / Play Store distribution.
- Editing or trimming video; AI search.

**Anti-goals — failure even if it "works"**
- Any path (UI button, API call, deploy option, cleanup bug) that deletes a **kept** clip, or deletes clips younger than the retention period (14 days since 2026-11-24, was 30) other than by David's own deletes (single, day or selection) or the 85 % → 75 % disk safety net (see `2026-11-24-usability-retention-design.md`).
- Footage reachable without logging in, or indexed by search engines.
- Anything that talks to the hub or wakes cameras.
- Filling the VPS root disk (only small thumbnails may live there).

## 3. Established facts

| Item | Value |
|---|---|
| Clips | `/app/webdav/cameras/<Camera>/<YYYY-MM-DD>/<HH-MM-SS>[-N].mp4` inside the app container = `/mnt/HC_Volume_107526857/cameras` on the VPS (15 GB Hetzner volume, bind-mounted). Fast-start H.264 1080p 15 fps + AAC, ~1 Mbit/s, typically 10–30 s, 0.5–4 MB. |
| Sidecar | Next to each clip, `<same stem>.json`, uploaded **after** the `.mp4` (so a `.json` means the `.mp4` is complete): `camera_name`, `camera_id` (stable UID, e.g. `9527000O0NWN1LLO`), `channel`, `start`/`end` (ISO 8601 with UTC offset, e.g. `2026-11-18T21:45:49+01:00`), `duration_seconds`, `triggers` (e.g. `["person"]`), `hub_model`, `hub_firmware`, `uploader_version`. |
| Volume marker | `/app/webdav/cameras/volume-marker.txt` ("camera clips live on the 15 GB volume - do not delete"); the uploader refuses to upload without it. Must never be deleted or listed as a clip. |
| Cameras | 4: Garage, Backyard, Living Room, Front door (Argus MagiCam). |
| Quota | WebDAV user `cameras`, 14336 MB. Uploads get HTTP 507 when full (uploader backs off; clips wait on the hub's SD card). |
| Volume | ext4, 14.7 GB, mounted `nofail`. Root disk 75 GB, ~68 % used (gallery 27 GB). |
| App | Spring Boot 4.1.2, Java 21 (Corretto), Postgres 16, Flyway (latest `V5__add_webdav_user_quota.sql`), Thymeleaf, Milton WebDAV, ffmpeg in the image (`VideoPosterGenerator` exists for the `/mae` gallery). Single admin user from `ADMIN_USERNAME`/`ADMIN_PASSWORD` (in-memory), form login at `/admin/login` with a login rate limiter (`LoginRateLimitFilter`), CSRF via cookie. |
| Deploy | `scripts/deploy.sh` from the MacBook (`DEPLOY_SSH_TARGET=davidneto-vps`, repo `/root/my-homepage`). Caddy fronts `davidneto.eu`. |
| WebDAV wipe paths today | `WebDavUserService.delete(id)` and `clearData(id)` (admin page), `clearDataByUsername` (self-service endpoint in `WebDavSelfServiceController`) all call `PerUserFileResourceFactory.clearUserData`, which deletes the user's whole folder — for `cameras`, the volume. |

## 4. Approaches considered (apps)

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | **Flutter**: one Dart codebase → native iPhone + Android apps | Native scrolling/video, mature plugins (video player, save to Photos/Gallery, biometrics, share). One codebase for one person. New language (Dart, Java-like). |
| B | Two native apps (Swift + Kotlin) | Best per-platform feel, but every feature built twice. |
| C | Wrap the web page (Capacitor) | Cheapest, but scrolling/video feel like a web page — against David's "responsiveness" reason. |

**David picked A.** Server side and web page are the same for all three.

## 5. Phases (build in this order; each is deployable on its own)

### Phase 1 — Protect the recordings (agreed)
1. **Protected WebDAV folders.** `WebDavUserService.delete`, `clearData` and `clearDataByUsername` refuse (clear error, nothing deleted) when the user's root contains `volume-marker.txt`. The admin page shows such users as "protected — on the camera volume" and hides/disables Delete and Clear data for them. Removing them requires a deliberate server-side step (documented, not in the UI).
2. **Single-file protection on WebDAV (my call, from David's "do not delete the camera recordings"):** for protected users (root contains `volume-marker.txt`) WebDAV `DELETE` and `MOVE` are refused (HTTP 403). `MKCOL` and `PUT` stay allowed — the uploader needs `PUT` overwrite for its crash-retry (it re-uploads the same clip to the same path). Deleting clips happens only through the Phase 2 API/web/app, which go through the app, not WebDAV. Consequence: David can't delete clips from Finder over WebDAV.
   - **Removing protection deliberately** (only if the recordings are really to be wiped): on the VPS, `rm /mnt/HC_Volume_107526857/cameras/volume-marker.txt`. The folder is then unprotected, the uploader stops uploading (it refuses without the marker), and the admin page offers Delete/Clear again. Recreate the marker with its exact text to protect it again.
3. **`deploy.sh --full`:** run the camera-volume check **before** `compose down`/volume removal; print that camera recordings on `/mnt/HC_Volume_107526857` are not touched; add "recreate WebDAV user `cameras` with the same password and quota 14336 MB" to the after-steps (pgdata wipe removes the account; recreating a user does not touch its folder).

### Phase 2 — Server: clip index, API, cleanup, disk status
- **Keep state lives on the volume (my call):** keeping a clip writes a zero-byte `<stem>.keep` file next to it; unkeeping deletes it. That file is the source of truth, so keep flags survive a `deploy.sh --full` (which wipes Postgres) or a rebuilt index. The database only caches it. The files are visible over WebDAV and cost no quota to speak of.
- **Clip index** (Postgres, new Flyway migration): one row per clip — relative path stem, camera name, camera id, channel, start (with offset) and end, duration, triggers, size, `kept` (cached from the `.keep` file) + `kept_at`, thumbnail path, indexed time. Unique on the path stem.
- **Volume guard for the indexer and cleanup:** both do nothing (and log) unless `volume-marker.txt` is present with its known text — so a volume that failed to attach (empty bind-mount directory) never makes the indexer drop rows or the cleanup act.
- **Indexer** (scheduled, every 30 s, and once at startup): walk the cameras folder; a clip is indexable only when its `.json` exists and parses (`.mp4` complete); insert new rows, generate a thumbnail (poster frame ~1 s in, ~480 px wide, JPEG) with the existing ffmpeg tooling; remove rows whose `.mp4` vanished. Ignores `volume-marker.txt` and anything not matching the layout. A malformed `.json` is logged and skipped, not fatal.
- **Thumbnails** live in a new small Docker volume on the root disk (my call: they are regenerable, ~20–40 KB each, so tens of MB at most), **not** in the WebDAV folder (would count against the quota and show up in WebDAV).
- **Cleanup** (scheduled daily, 03:30 VPS time — my call): delete clips whose **recording start** (from the index / `.json`, not file mtime) is older than **14 days** (30 until 2026-11-24; see `2026-11-24-usability-retention-design.md`) and that have **no `.keep` file on disk** (checked on disk at deletion time, not only from the cached flag): `.mp4`, `.json`, thumbnail, row. Configurable retention days. Logs a one-line summary. Never touches kept clips or the marker. If the index looks empty while files exist (e.g. indexer broken), the cleanup does nothing rather than act on stale data.
- **Manual delete** of one clip: removes `.mp4`, `.json`, thumbnail, row. Allowed for kept clips only after an explicit confirm (UI) — API requires unkeeping first (my call: two deliberate steps).
- **Disk status:** volume total/used/free, `cameras` quota used/limit, bytes in kept clips vs. not kept, root disk free, newest clip time per camera. Warning level when the volume or quota is ≥ 85 % or root disk ≥ 90 % (my call).
- **API** (JSON, under `/api/cameras/`, authenticated; my call on shape):
  - `GET clips?camera=&trigger=&from=&to=&kept=&before=<cursor>&limit=` — newest first, cursor pagination.
  - `GET clips/{id}`, `GET clips/{id}/video` (byte-range support — required by iPhone's player and for seeking), `GET clips/{id}/thumb`, `GET clips/{id}/download` (attachment).
  - `PUT clips/{id}/keep`, `DELETE clips/{id}/keep`, `DELETE clips/{id}`.
  - `GET status` (disk + newest clip per camera), `GET cameras` (names + ids).
  - `POST auth/token` (admin username + password → token), `DELETE auth/token` (logout).
- **Auth** (David: "just for me, but login would be nice"): the web page uses the existing admin session (form login at `/admin/login`). The apps use a **bearer token**: issued by `POST /api/cameras/auth/token` with the admin credentials, stored hashed in Postgres, valid 90 days and renewed on use (my call), revocable (logout, or "sign out all devices" on the web page). Same login rate limiting as `/admin/login`. Every endpoint, including video and thumbnails, requires auth; responses carry `X-Robots-Tag: noindex`.

### Phase 2b — Alerts via ntfy (David chose ntfy over email / in-app push / none)
- **Why:** David is away for a month at a time; a full disk or a dead uploader would otherwise go unnoticed until footage is lost on the hub's SD card.
- **Uploader heartbeat (small part-1 change):** after every cycle, at most every 5 minutes (my call), the Mac mini uploader `PUT`s `heartbeat.json` to the root of the `cameras` share: time, uploader version, whether the hub connection is OK, cameras seen, last error (text, no secrets). Also sent when the hub is unreachable but the VPS is — so "hub down" and "Mac mini down" can be told apart. Requires reinstalling the uploader on the Mac mini (`deploy/install.sh`).
- **Server checks** (every 5 minutes): alert when
  - no heartbeat for **60 min** ("Mac mini uploader silent"),
  - heartbeat says the hub has been unreachable for **60 min**,
  - the volume or the `cameras` quota is **≥ 85 %**, or the root disk **≥ 90 %**,
  - the daily cleanup fails.
- **Notification behaviour (my calls):** one push when a problem starts, one "resolved" push when it clears, a reminder every 24 h while it persists. Text only — never footage or thumbnails.
- **ntfy settings:** topic name and server URL from environment variables in the VPS `.env` (`CAMERA_NTFY_URL`, default `https://ntfy.sh`; `CAMERA_NTFY_TOPIC`, a long random name, never committed). David subscribes to the topic in the ntfy app on both phones. A "Send test alert" button on the web page.
- The indexer ignores `heartbeat.json` and `volume-marker.txt`; the web/app Storage view shows the last heartbeat time and hub status.

### Phase 3 — Web page
- At `/admin/cameras` (behind the existing admin login; linked from the admin menu). Server-rendered like the rest of `/admin` (Thymeleaf), with a little JavaScript for the player and infinite scroll. Works well on a phone browser.
- Layout: disk bar on top ("Kept 1.2 GB · Last 30 days 2.1 GB · Free 9.8 GB", amber/red when low), filter chips (camera, trigger, kept), day-grouped list of thumbnails with time, camera, trigger, duration, ★.
- Player overlay: native `<video>`, previous/next, Keep/Unkeep, Download, Delete (confirm; extra warning for kept clips).
- "Sign out all app devices" button (revokes tokens).

```
┌ Cameras ─────────────── Kept 1.2 GB · 30 d 2.1 GB · Free 9.8 GB ┐
│ [All] Garage Backyard Living Room Front door   [All] Person Motion  [★ Kept] │
│ Today                                                                        │
│  ▣ 21:57  Garage    person  10s  ☆                                           │
│  ▣ 21:49  Garage    person  10s  ★                                           │
│ Yesterday                                                                    │
│  ▣ 19:23  Backyard  person  11s  ☆                                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Phase 3b — Additions requested 2026-11-20
- **Alerts on/off** (David): a switch on the web page (and later the app) plus "Mute for 8 h". While muted nothing is pushed but problems stay tracked; anything still wrong is announced once alerts are back on; problems that started and cleared while muted stay silent.
- **Videos per camera** (David): a row of camera cards (latest thumbnail, clip count, last clip time) that opens that camera's clips.
- **Camera modes** (David: "changing the predefined modes is enough"): switch the hub's predefined modes remotely through a command the server stores and the Mac mini applies on its next check. Separate project after the web page; needs its own design (it relaxes the uploader's read-only rule for exactly this one setting).

### Phase 4 — Flutter apps (iPhone + Android)
- New `camera-app/` directory in this repo; one Flutter project; bundle/application id `eu.davidneto.cameras` (my call).
- Screens: **Sign in** (server URL prefilled `https://davidneto.eu`, admin username/password → token in Keychain/Keystore); **Clips** (same filters and day grouping as the web, infinite scroll, pull to refresh, cached thumbnails); **Player** (full-screen native player, swipe left/right for next/previous, Keep/Unkeep, Save to phone, Share, Delete with confirm); **Storage** (the disk view and newest clip per camera); **Settings** (Face ID / fingerprint lock on open, sign out).
- Video plays from `GET clips/{id}/video` with the bearer token header; Save/Share download the file first (show progress).
- Built and installed from this Mac: `flutter run`/`flutter install` to the Android phone; Xcode/`flutter run` with David's Apple team to the iPhone (his licence; no TestFlight/App Store).
- Handles: expired/revoked token → back to sign in; offline → cached list shown read-only with a banner.

### API as built (after the final review — for plans 2 and 3)
- `GET clips` paging: `nextBefore` is an opaque string cursor (`<start epoch ms>_<id>`), passed back as `before=`; it stays valid if that clip is deleted; malformed → 400.
- `GET cameras` → `[{"name": "Garage", "cameraId": "…"}]`.
- `/admin/**` and `/api/cameras/**` accept only the real admin login (ROLE_ADMIN); the family `/mae` password no longer opens `/admin`.
- Alerts: `uploader_silent` (60 min grace after app start), `uploader_failing` (heartbeat `last_ok` older than 60 min), `hub_down`, `hub_clock` (hub clock off by > 1 h), `volume_missing`, `disk_volume`, `disk_quota`, `disk_root`, `cleanup_failed`; undelivered pushes are retried every check.
- Cleanup deletes a clip only when it is older than 30 days by the hub timestamp **and** by its arrival time on the VPS; clips without a readable `.json` are removed by arrival time; kept clips never.
- WebDAV in the camera folder: uploads are atomic; PUT cannot write `.keep`, overwrite a kept clip, or empty an existing file.

## 6. Constraints

- No new public ports, hosts or Caddy changes: everything is under `davidneto.eu` (existing site).
- Must not disturb `/mae`, `/admin`, WebDAV, BordoFix.
- Root disk: only thumbnails (small) may be added there.
- Secrets stay out of git; the token secret/hash in Postgres; the apps never store the password, only the token.
- Commits: no `Co-Authored-By` lines (repo CLAUDE.md).

## 7. Left to the builder

- Exact table/column names and the migration number (`V6__…`).
- How the indexer reconciles efficiently (full walk every 30 s is fine at this scale: ~4 cameras × 30 days × tens of clips/day = a few thousand files).
- Range-request implementation (Spring's resource handling supports it).
- Flutter packages (video player, save-to-gallery, secure storage, biometrics, share, image caching) and state management.
- Thumbnail size/quality within the stated bounds.
- Test strategy per phase (unit + integration for the server, widget tests for the app, manual install on both phones as acceptance).
