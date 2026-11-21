# Person-while-away push notifications — design (short)

Addendum to `2026-11-18-camera-browser-design.md` (which left motion pushes to the Reolink app). Approved by David on 2026-11-21 (option A).

## Intent

David lives with the Reolink app's own notifications, but wants an ntfy push when a **person** is recorded while the hub is in **away** mode — and no more than about one push an hour. It is also the read half of the coming camera-modes project.

## Facts (read-only probe on 2026-11-21)

Hub: Reolink Home Hub Mini, firmware v3.3.0.609_26092101. Modes (reolink-aio "scenes"): `-1 off`, `1 disarm`, `2 home`, `3 away`; active was `home`. Reading uses Baichuan cmd 601 (`get_scene`) / 604 (`get_scene_info`) — both pass the uploader's read-only guard. Clip triggers stored on the server: `person`, `motion`, `animal` (the hub's own event name "people" is already mapped to `person`).

## Goals

- Uploader reads the active mode each cycle and writes it into every clip's sidecar (`"mode": "away"`) and into `heartbeat.json` (`"mode"`).
- Server: a newly indexed clip whose triggers include `person`, whose sidecar `mode` is `away`, and whose start is at most **30 min** before it is indexed, is announced:
  - no clip push in the last hour → push now: title `Person · <Camera> · <HH:mm>` (clip's own offset), body `Away mode`, tap opens `https://davidneto.eu/admin/cameras?camera=<Camera>&trigger=person`;
  - otherwise collected; when the hour since the last push ends, one summary: `<N> more person clip(s) while away: Garage 3, Backyard 1` (tap opens `?trigger=person`), and that starts the next hour.
- The alerts switch and "Mute 8 h" cover these pushes: while muted nothing is sent and pending counts are dropped.
- The page's health line shows the mode: `… · Hub OK · mode: home` (omitted when the heartbeat has none).

## Non-goals

Switching modes (next project); notifications for motion/animal or other modes; pictures in pushes (ntfy.sh topics are readable by anyone with the name — text only); per-camera rate limits.

## Anti-goals

- Any hub write: the guard stays fully on; reading the mode must never break or slow a cycle (failure → mode unknown, clip still uploads, sidecar `mode` null).
- A flood: after an outage or a re-index, old clips never push (30 min cut-off), and the hourly cap holds across app restarts.
- Pushes while muted.

## Approaches

A (chosen): stamp the mode per clip at upload. B (rejected): server uses the latest heartbeat mode at arrival — wrong for clips uploaded late.

## Decided (my calls, stated to David)

- Mode is read once per cycle after listing clips; names come from `get_scene_info` at connect (best effort). Value written is the hub's name in lower case (`away`, `home`, `disarm`, `off`) or null.
- Heartbeat is re-sent promptly when the mode changes (like a hub_ok change), so the page shows it within a cycle.
- Push state (last push time, pending counts per camera) is persisted in `camera_setting` so a deploy mid-hour neither re-pushes nor loses a pending summary.
- Summary check runs every minute on the existing scheduler (disabled in tests).
- Priority `high`, tag `bust_in_silhouette` for person pushes.

## Left to the builder

Class names and where the hourly logic lives (one small component next to `AlertChecker`); exact Jackson/ntfy header code (`Click` header); test fakes.
