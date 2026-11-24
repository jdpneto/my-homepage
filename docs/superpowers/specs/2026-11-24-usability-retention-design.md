# Mode-switch feedback, away pushes on motion, collapsed days, 14-day retention — design (short)

From David's phone use on 2026-11-24. Decisions by David: away pushes on **person or motion**; **14 days + safety net**.

## Evidence

- Mode buttons: the page highlights `current` (heartbeat mode). The uploader reports the request result at once but the heartbeat `mode` only after the cycle, so the page stops polling at "applied" with the old mode highlighted until a reload.
- Push 13 min "late": the 18:19–18:20 clips (away) were tagged `motion`; the first `person` clip (18:32:52) pushed at 18:34 — pipeline latency ≈ 1 min.
- Volume: ~400–500 clips / ~500 MB per day (David's bulk deletes: 516 MB on 23 Nov, 538 MB on 24 Nov). 30 days ≈ 15 GB > the 14 GB quota.

## Goals

1. **Mode switch** (page): tapping a mode immediately marks that button as switching (distinct style + `aria-busy`, status "Switching to away…"); on `applied` the requested mode is highlighted at once and the page keeps polling `GET /api/cameras/mode` (every 3 s, up to 60 s) until `current` equals it; failed/expired restore the previous highlight. **Uploader**: after a switch whose read-back matches, the heartbeat `mode` is set together with the `mode_request` result (same early send).
2. **Away pushes**: a clip qualifies when its sidecar `mode` is `away` and its triggers contain `person` **or** `motion` (animal-only does not), start ≤ 30 min before indexing; same summary, but **two hourly slots** so motion never holds back a person (review fix): a motion clip pushes at once only when no push went out in the last hour and nothing is pending; a person clip pushes at once when no person push (single, or a summary with a person in it) went out in the last hour, unless a summary is due right now (it joins it). A person push that finds the motion hour free starts it too. State row gains `last_person_push`; an undelivered person push is retried on the next minute flush once its slot is free. Copy: `Person · <Camera> · <HH:mm>` when person is among the triggers, else `Motion · <Camera> · <HH:mm>`; summary `<N> more clip(s) while away: Garage 3, Backyard 1`. Tag stays `bust_in_silhouette` for person, `warning` for motion.
3. **Collapsed days** (page): today's clips render open; older days render as one collapsed row each: `<Day> · N clips · X MB ▸` (from a new `GET /api/cameras/days?camera=&trigger=&kept=` → `[{"day":"2026-11-23","count":412,"bytes":516292498}]`, days in the cameras' own offset, newest first, respecting filters). Tapping a collapsed day expands it and loads that day's clips via `GET /api/cameras/clips` with `from`/`to` for that day (and the filters), paging with "Load more" inside the day; tapping again collapses. Delete-a-day and Select-day keep working on a collapsed day (they load the day first). The server-rendered page no longer renders older days' clips (fast first load). Empty states unchanged.
4. **Retention 14 days + safety net**: `app.cameras.retention-days` default **14** (two-clock rule unchanged). Safety net in the 03:30 job and also checked every 10 min: if the volume (or the cameras quota) is above **85 %** used, delete the oldest non-kept clips (by start time) until below **75 %**; kept clips never. Logged at WARN with counts; an ntfy alert says it happened ("Disk safety net freed X GB (N oldest clips)"). Review fix: if deleting every non-kept clip still would not get below 75 % (kept clips or other data on the volume), delete only the oldest clips needed to get back under 85 % (nothing when even that is out of reach) and push "Camera disk full of kept clips or other data" instead. Each of the two pushes at most once per 24 h (time stored in `camera_setting`: `safety_net_freed_push_at`, `safety_net_full_push_at`); later runs only log. Uploader: backfill never longer than **14** days (`MAX_BACKFILL_DAYS = 14`; a larger config value is capped with a warning), version **0.3.2**.

## Anti-goals

Deleting a kept clip; deleting clips younger than 14 days except by David or by the safety net above 85 %; re-uploading clips the cleanup removed; a mode highlight that lies (shows a mode the hub hasn't confirmed without the "switching" look).

## Spec changes

`2026-11-18-camera-browser-design.md` anti-goal "never delete clips younger than 30 days" becomes "younger than 14 days, except David's own deletes and the 85 % → 75 % safety net".
