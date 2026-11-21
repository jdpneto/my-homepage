# Keep from the list, bulk keep, bulk download, iOS-friendly download — design (short)

Requested by David on 2026-11-21 after using the home-screen app ("the star is already there — make it tappable"; "bulk system looks fine"; "bulk download would be cool, especially for iOS").

## Goals

1. **Tappable star per row** (`.clip-kept`, already shown: right side on phones, under the row on desktop): one tap keeps/unkeeps via the existing `PUT/DELETE /api/cameras/clips/{id}/keep`, with the same double-tap quiet window as the player's Keep. It must become its own `<button>` (sibling of `.clip-open`, never nested), `aria-pressed`, label "Keep clip"/"Unkeep clip", ≥ 44 px touch target on touch screens. Opening the clip still works by tapping the rest of the row; in Select mode the star is inert (the row toggles selection).
2. **Bulk keep** — Select bar gets **Keep** (one tap, guarded): `POST /api/cameras/clips/keep {"ids":[…]}` (1–500, deduped; same auth/CSRF as other writes; 503 when the volume is missing) → 200 `{"kept":[ids],"keptBytes":n,"notFound":[ids],"failed":[ids]}` (already-kept clips count as kept; each through `ClipService.keep(id, true)` — `.keep` file first). Toast "Kept N clips · X MB" (+ " · M already gone"); rows get ★ and stay ticked (so Download can follow). Kept rows can be ticked like any other (revised after review: kept clips are the ones most likely to be downloaded): Keep sends only the non-kept ticked clips ("All already kept" when none), Download includes kept ones, Delete skips them ("Yes, delete N · K kept stay"; only kept ticked → "Kept clips can't be deleted — unkeep first").
3. **Bulk download** — Select bar gets **Download**:
   - iPhone/iPad (Web Share with files available — `navigator.canShare({files})` — on iOS/iPadOS or in the home-screen app): fetch each selected clip's `/download` as a blob with progress ("Preparing 3/12…"), then the button becomes **Save N videos**; that second tap calls `navigator.share({files})` (needs a fresh tap) → share sheet → "Save N Videos" to Photos. At most 30 clips or 300 MB per share (toast otherwise: "Select up to 30 clips to save at once"). Cancel/leaving select mode drops the blobs.
   - Elsewhere: one zip — `GET /api/cameras/clips/zip?ids=1,2,3` (1–500, deduped; admin session or bearer) → `application/zip`, `Content-Disposition: attachment; filename="cameras-<YYYY-MM-DD-HHmm>.zip"`, `Cache-Control: private, no-store`, streamed (no temp file), entries `<Camera>/<YYYY-MM-DD>/<HH-MM-SS>.mp4` (the clip stem), deflate level 0 (videos are already compressed). Unknown ids / missing files are skipped; none found → 404.
4. **Single Download in the home-screen app on iOS** (player bar): same two-step share ("Preparing…" → **Save video**) instead of navigating to the file (which traps the user in a viewer with no way back). Normal browsers keep the plain download link.

## Anti-goals

A nested interactive element; keep/unkeep toggled twice by a double-tap; a zip endpoint reachable without login or usable as a path traversal (ids only, stems come from the DB and go through `ClipFiles`); holding hundreds of MB in phone memory.

## Left to the builder

Bar layout at 320–375 px with four buttons (icons with labels/aria-labels are fine); progress UI details; iOS detection specifics (keep it feature-detection-first).
