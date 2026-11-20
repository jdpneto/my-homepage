# Camera clips: bulk delete — design (short)

Addendum to `2026-11-18-camera-browser-design.md`. Approved approach and UI sketch: conversation of 2026-11-20.

## Intent

David wants to clear clips himself to keep the space tidy — mostly junk in bulk (e.g. a day of Garage clips triggered while he was in the room), not because the disk is tight (86 MB of 15 GB used on 2026-11-20; the 30-day cleanup and the disk alerts stay as they are). Today he can only delete one clip at a time from the player.

## Goals

- **A. Delete a day:** each day heading shows `<Day> · N clips · X MB` and a **Delete** button. It deletes that day's clips *within the active filter* (camera / trigger / kept).
- **B. Select mode:** a **Select** button beside the filters; rows become tick boxes; a sticky bar shows `N selected · X MB [Delete] [Cancel]`; each day heading gets a "select whole day" tick.
- Both use the page's two-tap confirm, with exact numbers at the moment of confirming ("Yes, delete 9 clips · 17 MB", "Yes, delete 7 · 2 kept stay").
- Afterwards: rows removed, toast "Deleted 12 clips · 34 MB freed" (+ "2 kept skipped" / "1 was already gone" when it applies), storage line's free space refreshed.

## Non-goals

- No undo / trash (files are really deleted; the two-tap confirm is the safety net, as for single clips).
- No storage-breakdown screen (option C), no "delete everything older than …" rule.
- Phone app UI (plan 3) — it will reuse the endpoint.

## Anti-goals (a failure even if it works)

- A kept clip deleted by any bulk path — refused on the server, not only hidden in the page.
- A clip deleted that David could not see when he confirmed (e.g. one that arrived a minute ago, or one outside the active filter).
- A double-tap or held key deleting a second batch (same guards as the single delete: `CONFIRM_MIN_MS`, quiet window after acting, no auto-repeat).

## Approaches

1. **Explicit list of IDs — chosen.** One endpoint deletes exactly the IDs the page sends; "delete day" first loads the rest of that day, then sends those IDs. Only what was visible goes; the app can reuse it.
2. Server deletes by rule ("this day, this camera") — rejected: can delete clips David never saw.
3. Page loops the single-clip DELETE — rejected: N requests, slow on a phone, a dropped connection leaves a half-deleted day without a clear report.

## Decided

**Endpoint** `POST /api/cameras/clips/delete` (POST because DELETE bodies are poorly supported), body `{"ids":[1,2,3]}`. Same chain/auth/CSRF as the other camera writes (admin session + XSRF, or bearer for the app).
- 1–500 IDs, duplicates collapsed; empty, more than 500, or non-numeric → 400. The page splits larger selections into chunks of 500.
- Each ID goes through the same steps as `ClipService.delete` (kept check against the `.keep` file on the volume, then mp4 + json, thumbnail, DB row). Kept → skipped; unknown → reported as not found; an I/O failure on one clip → reported as failed, the rest continue.
- Response 200: `{"deleted":[ids],"freedBytes":n,"skippedKept":[ids],"notFound":[ids],"failed":[ids]}`.
- Logged at INFO as one line: counts and freed bytes (no clip names needed).

**Page**
- Each `li.clip` (server-rendered and JS-appended) carries `data-size` (bytes). Heading numbers come from the loaded rows; when the day may continue past the last loaded page (last loaded row is in this day and there is a next cursor) the heading shows `50+ clips` style "N+".
- Delete day: first tap loads pages until the day ends (or the list ends), then arms with exact count/size; kept rows in the day are excluded and named in the label. Second tap (≥ `CONFIRM_MIN_MS` later, within 4 s) sends the IDs.
- Select mode: tapping a row toggles it instead of opening the player; kept rows cannot be ticked; Escape or Cancel leaves select mode; infinite scroll keeps working; "select whole day" loads the rest of the day first. The sticky bar must not cover the last row, and has 44 px targets on touch.
- After a delete: remove `deleted` and `notFound` rows; mark `skippedKept` rows kept; leave `failed` rows and say so in an error toast; empty-state rules as today; refresh the storage line from `GET /api/cameras/status`.
- Signed-out (401, or 403 + failed status probe) → the existing "Signed out" banner, no retry.

## Left to the builder

Exact markup/CSS of the heading button, tick boxes and bar (follow the existing page style and the phone layout); how the storage line is re-rendered from the status JSON; transaction boundaries inside the service (per clip is fine — files are the truth).
