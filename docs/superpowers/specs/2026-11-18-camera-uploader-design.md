# Camera Clip Uploader — Design (Part 1 of 2)

**Status:** approved by David 2026-11-18
**Date:** 2026-11-18
**Supersedes:** `reolink_vps_camera_architecture_setup_spec.md` (Gemini draft). Its WireGuard/router design is **rejected** — see "Rejected approach" below.

## 1. Intent

David has Reolink battery cameras recording motion clips onto the microSD card of a Reolink Home Hub Mini at home. He wants an **automatic off-site copy of every clip on his VPS** (`davidneto.eu`), with no cloud subscription, so footage survives if the hub is stolen or damaged.

This is **part 1**: get clips onto the VPS reliably. **Part 2** (separate design, later) adds a page in the homepage app to browse/play clips, retention cleanup, disk monitoring and alerting. Part 1 must leave clips where part 2 can read them directly.

## 2. Goals, non-goals, anti-goals

**Goals**
- Every motion clip the hub records ends up on the VPS, automatically, typically within ~1 minute of the clip finishing.
- Survives reboots, power cuts, VPS downtime and network blips without losing or duplicating clips (catches up from the hub's SD card afterwards).
- Runs unattended for a month+ while David is on holiday.
- Clips land in a predictable folder layout readable by the homepage app.

**Non-goals (part 1)**
- Browsing/playing clips, retention/deletion, disk monitoring, alerting when the uploader stops — all part 2.
- Live view or continuous recording.
- Notifications (the Reolink app already does this).

**Anti-goals — failure even if it "works"**
- **Anything that breaks the Reolink phone app** (David uses it to arm/disarm). The hub's own internet connectivity and the home router must not be touched.
- **Anything that keeps the battery cameras awake** (live RTSP streaming, frequent wake-ups). Only talk to the hub, which is mains-powered; never request live streams.
- **Changing hub settings.** The uploader is strictly read-only towards the hub (see §6.3 — the library we use will silently change settings unless prevented).
- Opening ports on the home internet connection.
- Filling the VPS disk.

## 3. Established facts (verified 2026-11-16 from David's Mac on the home LAN)

| Item | Value |
|---|---|
| Hub | Reolink Home Hub Mini, firmware `v3.3.0.609_26092101`, LAN IP `192.168.1.24` (static DHCP reservation), user `admin` |
| Cameras on the hub | **2**: channel 0 `Garage`, channel 1 `Backyard` (both Argus MagiCam). Gemini's doc said 4 — wrong. More may be added later. |
| Hub ports open | 9000 (Reolink "Basic Service"/Baichuan), 443 (HTTPS API), 80 (redirects to HTTPS), 554 RTSP, 1935 RTMP, 8000 ONVIF. HTTPS only started listening **after a hub reboot**. RTMP was switched on by `reolink-aio` during testing (see §6.3); David is fine leaving it for now. |
| Library | `reolink-aio` 0.23.1 (Python). `Host(...)` + `host.baichuan.search_vod_type(ch, start, end, stream="main")` lists clips; `host.baichuan.download_mp4(ch, start, end, stream="main")` downloads one. |
| Test result | Listed 5 + 3 motion clips for the last 24h; downloaded a 30 s clip (4.13 MB) in **2.1 s**. Downloading from the hub's SD card does not wake cameras. |
| Clip format | H.264 1920×1080 @ 15 fps + AAC, ~1.06 Mbit/s → ~4 MB per 30 s. Output of `download_mp4` is a **fragmented MP4**. |
| Volume estimate | ~8 clips/day observed → ~30 MB/day, ~1 GB/month. Busy days could be several× that. |
| VPS | Hetzner, AMD EPYC Rome, 75 GB root disk, **~21 GB free** after cleanup (gallery uses 26 GB). Repo at `/root/my-homepage`, Docker Compose stack: Caddy + Spring Boot 4.1.2 app (Corretto 21) + Postgres. Also hosts BordoFix (prod + staging compose projects). |
| Video volume | **Dedicated 15 GB Hetzner volume** added 2026-11-18 for camera clips, so video never touches the root disk the apps use. ext4, `/dev/sdb`, mounted at `/mnt/HC_Volume_107526857`, fstab: `/dev/disk/by-id/scsi-0HC_Volume_107526857 … ext4 discard,nofail,defaults 0 0` (`nofail` = server still boots if the volume is missing). |

**Important compatibility finding:** the homepage's gallery ingest rejects the fragmented MP4 from `download_mp4` ("unsupported or unrecognized media type"); the same clip remuxed with `ffmpeg -c copy -movflags +faststart` is accepted and gets a poster/thumbnail. Hence §6.4.

## 4. Rejected approach (Gemini draft)

WireGuard client on the TP-Link AX3000 with the hub in the VPN "Device List", VPS pulling from `192.168.1.24` through the tunnel. Rejected because:
- Putting the hub in the VPN device list routes **all** the hub's internet traffic via the VPS → risks breaking the Reolink app (arm/disarm), which is an anti-goal.
- Consumer-router VPN clients generally don't accept connections initiated from the server side into the LAN; unverified and fragile.
No router changes, no WireGuard. Section 3 of the Gemini doc is void.

## 5. Architecture

```
[Cameras] ──Wi-Fi──▶ [Home Hub Mini 192.168.1.24]  (records clips to microSD)
                              │  LAN, port 9000/443, read-only
                              ▼
                     [Mac mini M1 at home]  camera-uploader service (launchd daemon)
                              │  HTTPS (outbound only), WebDAV PUT
                              ▼
            https://cloud.davidneto.eu  (existing WebDAV in homepage app)
                              │
                              ▼
     /mnt/HC_Volume_107526857/cameras  (15 GB Hetzner volume, bind-mounted into the app container)
                              = /app/webdav/cameras/<Camera>/<YYYY-MM-DD>/<HH-MM-SS>.mp4 (+ .json)
```

- **Home host: David's spare Mac mini M1** (~7 W idle, ~€1–1.50/month), wired Ethernet to the AX3000. Chosen over buying a Raspberry Pi. David decided this.
- **Transport: the existing per-user WebDAV** at `cloud.davidneto.eu`, via a dedicated WebDAV user `cameras` (David agreed WebDAV is OK). No new ports, containers, or Caddy changes on the VPS.
- **Storage: the dedicated 15 GB volume** (David's decision — keeps video off the root disk the homepage and BordoFix use). WebDAV user roots are `WEBDAV_ROOT_DIR/<username>/` (see `PerUserFileResourceFactory`), so the `cameras` user's root is `/app/webdav/cameras`. That one directory is **bind-mounted from the volume** — `/mnt/HC_Volume_107526857/cameras:/app/webdav/cameras` in `docker-compose.yml` `app.volumes` — nested inside the existing `webdav_data` named volume (Docker supports nested mounts; other WebDAV users are unaffected).
- **Quota: 14 GB** (14336 MB) on the `cameras` user, leaving ~1 GB headroom on the 15 GB volume for ext4 overhead and in-flight uploads. Was 5 GB when clips were going to share the root disk.
- Part 2 reads clips directly from `/app/webdav/cameras/...` inside the app container.

## 6. Uploader behaviour

### 6.1 Polling loop
- Every **30 s** (configurable), unless the previous cycle is still running — never overlap cycles (David: "poll more often unless files are transferring").
- Each cycle: for every channel the hub reports, search motion recordings over a window from *(last fully-processed point − overlap)* to *now*; skip clips already uploaded; download + process + upload the rest, oldest first.
- Iterate over whatever channels the hub reports; don't hard-code 2 cameras.
- Search **all clip trigger types** (motion, person, animal, …), not only `VOD_trigger.MOTION` — a clip should not be missed because of how the camera classified it. De-duplicate by clip identity.
- **Don't take clips that may still be growing:** only process a clip whose end time is at least ~20 s in the past (settle delay, configurable). Otherwise pick it up next cycle.

### 6.2 Exactly-once-ish delivery
- Clip identity = `(channel UID or channel index + camera name, clip start time, clip end time)`. Builder picks the exact key; it must be stable across restarts.
- Persist a local state store (SQLite or a JSON file, builder's choice) of uploaded clips. A clip is marked uploaded **only after** the WebDAV PUT returns 201/204.
- Writes to the state store must be crash-safe (atomic replace / SQLite transaction).
- Re-uploading the same clip to the same path is harmless (overwrites identical content) — acceptable fallback if state is lost.

### 6.3 Read-only towards the hub (hard requirement)
`reolink-aio` will, when the HTTP(S) login fails, **log in over port 9000 and switch on the HTTPS and RTMP ports on the device by itself** (`Host._login_try_ports` → `baichuan.set_port_enabled(...)`). This happened during testing. The uploader must make this impossible, e.g. by:
- patching/overriding `Baichuan.set_port_enabled` (and any other `set_*` the login path can call) to raise instead of acting, and
- never calling any `Host.set_*` API.
If the hub's HTTPS API is unavailable, the uploader logs an error and retries later; it never "fixes" the hub. Builder should verify which login path is taken with HTTPS on, and that downloads work; Baichuan-only mode (`bc_only=True`) flips to HTTP automatically when the hub advertises HTTPS, so don't rely on it alone.

### 6.4 Processing before upload
- Download with `stream="main"` (full 1080p).
- **Remux** the fragmented MP4 to a regular, fast-start MP4: `ffmpeg -i in.mp4 -c copy -movflags +faststart out.mp4` (no re-encode; seconds of CPU at most). This makes clips seekable in browsers and acceptable to the app's existing media handling. ffmpeg is installed via Homebrew on the Mac mini.
- Work in a temp dir; delete temp files after upload.

### 6.5 Upload
- WebDAV over HTTPS to `https://cloud.davidneto.eu/<Camera>/<YYYY-MM-DD>/<HH-MM-SS>.mp4` as user `cameras` (Caddy rewrites to `/webdav/...`; user root is their own folder, so no `cameras/` prefix in the URL).
- Create missing collections with `MKCOL` (camera folder, date folder) before PUT; treat 405 "already exists" as fine.
- **Send `Content-Length`** (no chunked transfer): the app's `WebDavQuotaFilter` pre-checks Content-Length against the quota and aborts chunked uploads that exceed it.
- Folder/file names use the **hub's local clip start time** (the hub and Mac mini are both in David's timezone). Camera folder = hub camera name, sanitised for filesystem/URL safety (e.g. spaces OK but strip `/`, `\`, control chars). If two clips start in the same second on the same camera (including the repeated hour when clocks go back for DST), append a suffix rather than overwrite.
- **Metadata sidecar (my call):** next to each clip, upload `<HH-MM-SS>.json` with what only the hub knows and is lost once the SD card overwrites the clip: trigger types (motion/person/animal/vehicle/…), start and end time (ISO 8601 with UTC offset), channel index, a stable camera identifier (camera UID if the library exposes it, else channel index + model), camera name at upload time, hub model/firmware, uploader version. Upload the `.mp4` first, then the `.json`; the clip only counts as uploaded once both succeed. Part 2 can use this to filter (e.g. "person only") and to merge history if a camera is renamed in the Reolink app (folders follow the display name, so a rename starts a new folder — acceptable, part 2 can group by the stable id).
- Error handling:
  - Network error / 5xx / timeout → leave clip pending, retry next cycle with backoff (cap e.g. 5 min). The hub's SD card is the buffer.
  - **507 (quota full)** → log loudly, stop uploading this cycle, retry later (part 2 adds retention; until then David raises the quota or deletes old clips).
  - 401 → log loudly (bad credentials), back off hard (e.g. 15 min). The homepage app locks a WebDAV user out after repeated failed logins, so a wrong password must not turn into a retry storm.
- **Hub sessions:** keep one logged-in hub session across cycles (re-login only on failure) rather than logging in every 30 s, to avoid disturbing the phone app's sessions on the hub; back off on hub login failures for the same lockout reason.

### 6.6 Backfill
On first run (empty state), upload everything still on the hub's SD card from the **last 30 days** (my call; configurable). Then continue normally. After an outage, the normal window logic catches up automatically; the window should reach back far enough to cover a multi-day outage (e.g. look back to the oldest not-yet-uploaded point, capped at 30 days).

### 6.7 Logging
- Log to a rotating file on the Mac mini (e.g. `~/Library/Logs/camera-uploader/` or `/usr/local/var/log/`) plus stdout for `launchd`.
- One line per uploaded clip (camera, start, size, duration of transfer); warnings for retries; errors for 401/507/hub unreachable.
- Never log passwords.

## 7. Mac mini host setup

- **Runs as a `launchd` LaunchDaemon** (starts at boot, before login; restarts on crash via `KeepAlive`). Runs as a dedicated non-admin user or David's user — builder's choice, documented.
- Power settings (installer prints or applies via `pmset`, with David's consent): never sleep (`sleep 0`, `disksleep 0`), `autorestart 1` (start after power failure), keep network up.
- Wired Ethernet to the AX3000 recommended.
- **FileVault:** if on, the Mac waits at the unlock screen after a power cut and the service won't start. **David's decision** — open question (§10).
- Install script in the repo that: creates the venv, installs pinned deps, installs ffmpeg via Homebrew if missing, writes the config file template, installs/loads the LaunchDaemon plist. Idempotent (re-runnable for upgrades). An uninstall path too.

## 8. Configuration & secrets

- Config file on the Mac mini only (e.g. `/usr/local/etc/camera-uploader/config.toml` or `.env`), `chmod 600`, **never committed**:
  - hub host, hub user, hub password
  - WebDAV base URL, user `cameras`, password
  - poll interval, settle delay, backfill days
- Repo contains only a `config.example` with placeholders.
- **Change the hub password** after setup — the current one was exposed in a chat transcript during testing (`~/reolink.txt` on David's MacBook should then be deleted).

## 9. VPS changes

- On the VPS: `mkdir -p /mnt/HC_Volume_107526857/cameras` (owned by the user the app container runs as — currently root).
- `docker-compose.yml`: add `- /mnt/HC_Volume_107526857/cameras:/app/webdav/cameras` to `app.volumes`; commit, push, deploy with `scripts/deploy.sh`.
- **Guard against writing to the root disk if the volume isn't mounted** (it's `nofail`, so the server boots without it): if `/mnt/HC_Volume_107526857` isn't mounted, the bind-mount source would silently be an empty directory on the root disk. Builder adds a cheap guard, e.g. a marker file on the volume (`/mnt/HC_Volume_107526857/cameras/.volume-marker`) that the deploy script checks and refuses to deploy without, and/or the uploader treats a missing marker as an error (via a WebDAV PROPFIND of the marker). Builder's choice of mechanism; the requirement is that clips never land on the root disk.
- Create WebDAV user `cameras` in `https://davidneto.eu/admin/webdav-users` with a **14336 MB** quota (password ≥ the app's minimum length). David does this by hand.
- No Java code changes to the homepage app in part 1 (compose + deploy-script changes only).

## 10. Decisions (David, 2026-11-18)

1. **Hub account: `admin`, no limited user "for now".** The code-level guard in §6.3 is therefore the *only* protection against settings changes: it must be airtight and covered by tests (every `reolink-aio` setter/port-enabling path blocked, verified with a test that fails if any `set_*` is reached). Keep the hub username configurable so a limited user can be swapped in later without code changes.
2. **FileVault: off** (verified `fdesetup status` → Off). The installer still checks it and warns if it's ever turned on.

## 10a. Mac mini facts (verified 2026-11-18 over SSH)

| Item | Value |
|---|---|
| Host | `Davids-Mac-mini.local`, user `davidneto`, **static IP 192.168.1.23** set on the Mac (wired `en0`); AX3000 DHCP pool starts at .25 |
| OS | macOS 27.2.1, Apple silicon (arm64), 75 GB free |
| Power | `sleep 0`, `disksleep 0`, `autorestart 1`, `womp 1` |
| SSH | key-only (`~/.ssh/id_ed25519_macmini` on the MacBook, `from="192.168.1.0/24"`), password login refused, `AllowUsers davidneto@192.168.1.*`; MacBook alias `ssh macmini` |
| Tooling | Homebrew at `/opt/homebrew`; **no ffmpeg; no usable python3** (Command Line Tools missing → `/usr/bin/python3` is a stub). Installer must `brew install python@3.13 ffmpeg` (or equivalent) and never rely on `/usr/bin/python3`. |
| Hub reachability | 192.168.1.24 ports 9000 and 443 reachable from the Mac mini |
| Login screen | Mac boots to the login window (FileVault off); the LaunchDaemon runs without anyone logging in |

## 11. Left to the builder

- Python version, project layout under `camera-uploader/` in this repo, state store format, exact clip-identity key, retry/backoff constants, log location, tests.
- How exactly to enforce read-only (§6.3), as long as it's verified.
- Whether to use `reolink-aio`'s HTTPS or Baichuan path for search/download — whichever works reliably on this hub with the read-only guarantee.
- Pin `reolink-aio` to a tested version (0.23.1 was tested).

## 12. Testing approach

- Unit tests with a fake hub/WebDAV for: dedupe, settle delay, crash between upload and state write, 507/401/5xx handling, backfill window, filename collisions.
- Manual end-to-end on David's MacBook on the home LAN against the real hub and a local or real `cameras` WebDAV user before installing on the Mac mini.
- After install on the Mac mini: reboot test (service comes back), unplug-network test (catches up), confirm clips appear under `/app/webdav/cameras/` on the VPS and play in a browser.

## 13. Known follow-ups for part 2

- Browse/play page in the homepage app reading `/app/webdav/cameras/`.
- Retention (e.g. delete clips older than 30 days) and quota/disk monitoring — both the 15 GB video volume and the root disk (~72% full; the gallery is 26 GB).
- Alert when no clip has arrived for N hours / uploader heartbeat.
- Existing app bugs noticed in passing: malformed Basic auth header → 500 instead of 401 on WebDAV; Milton `applicationContext.xml` startup error noise.
