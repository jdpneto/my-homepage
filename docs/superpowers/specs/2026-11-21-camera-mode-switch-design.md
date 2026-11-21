# Remote camera mode switch — design (short)

Addendum to `2026-11-18-camera-browser-design.md` (Phase 3b "camera modes: next project") and `2026-11-21-away-person-push-design.md` (the read half). Approved by David on 2026-11-21 (option A; Disarm asks twice).

## Intent

Switch the hub between its predefined modes from `/admin/cameras` (and later the phone app) while away from home, without breaking the Reolink app — which David still uses to arm/disarm.

## Facts

Hub modes (reolink-aio scenes): `-1 off`, `1 disarm`, `2 home`, `3 away`. Switching = Baichuan cmd 602 via `Baichuan.set_scene(scene_id)` — the same thing the Reolink app does. The uploader's guard blocks `set_scene` today (it wraps it; the original is `Baichuan.set_scene.__wrapped__`).

## Goals

- Page: the health line's mode becomes a switch `Mode: [Disarm] [Home] [Away]` (current one highlighted). Home/Away: one tap. Disarm: two taps (same arm/confirm as Delete). Status: "Switching to away…" → "Away ✓" when the heartbeat confirms, or "Not applied: <reason>" / "Expired — the Mac mini didn't pick it up".
- Server: `POST /api/cameras/mode {"mode":"away"}` writes `mode-request.json` at the camera root (atomic): `{"id": "<uuid>", "mode": "away", "requested_at": iso, "expires_at": iso (+5 min)}` → 202 `{"id", "mode", "status":"pending"}`. Modes accepted: `disarm`, `home`, `away` (never `off`). `GET /api/cameras/mode` → `{"current": <heartbeat mode or null>, "request": {"id","mode","status","error","requested_at"} | null}` with status `pending` | `applied` | `failed` | `expired`. Same auth/CSRF as other camera writes (admin session + XSRF, or bearer). INFO log per request and per result (no secrets).
- WebDAV: nobody may write, move, copy or delete `mode-request.json` over WebDAV (like `volume-marker.txt`), so the Mac mini's upload password cannot switch modes; only the server (filesystem) writes it.
- Uploader: at the start of each cycle (after the volume-marker check) GET `mode-request.json`; if valid, unexpired, not applied before (id remembered in the state DB), and its mode names a scene the hub listed with id ≥ 0 → call the original `set_scene` for that id; re-read the mode; report in the heartbeat `"mode_request": {"id","status":"applied"|"failed","error","at"}` (sent at once). An expired or unknown-mode request is reported `failed` with a reason. Never breaks the cycle.

## Non-goals

Editing what a mode does, schedules, per-camera switches, `off`.

## Anti-goals

- Any other hub write: the guard stays fully on; the single exception is a function that only accepts a scene id the hub listed (≥ 0).
- A stale request applied late (5-min expiry checked on the Mac mini too) or twice (id remembered).
- Anyone but the admin/app (or the server itself) able to request a switch.

## Approaches

A (chosen): request file in the cameras folder, protected from WebDAV writes. B (rejected): an API the Mac mini polls — today's API tokens can do everything (delete clips).

## Decided (my calls)

5-min expiry; the page polls `GET /mode` every 3 s while pending (stops after 6 min); a newer request replaces an older pending one; `failed` reasons are short ("hub refused", "unknown mode", "expired before pickup", "hub not connected").

## Left to the builder

Exact class/file names; JSON parsing details; button styling following the page.
