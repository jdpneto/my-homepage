# Memorial Gallery (`/mae`) — Design Specification

## Overview

Add a private, login-walled photo and video gallery at `https://davidneto.eu/mae`, dedicated as a memorial. Family members and friends with a shared password can browse the collection (a year/month archive plus a "recently added" view) and contribute new photos and videos through a browser uploader. Bulk operations and power-user contributions are handled through a separate WebDAV drop folder. The gallery is unlinked from the main site and excluded from search engines.

The feature lives in a new `com.davidneto.homepage.gallery` package, wired into the existing Spring Boot 3.4 / Java 21 / PostgreSQL / Milton stack. The existing `image` table (used for blog and page asset attachments) is **not** reused — the gallery has different semantics, ownership, and access control, and conflating them would couple unrelated lifecycles.

## Scope

**In scope:**

- A new `gallery_item` table (Flyway migration `V4__add_gallery.sql`) and managed filesystem layout under a new `app.gallery.root-dir`.
- Single ingest pipeline (browser upload, WebDAV drop folder, one-time bulk importer) with EXIF/ffprobe metadata extraction, content-hash dedupe, thumbnail and display-size derivative generation, and atomic finalize.
- Year/month archive (driven by EXIF `DateTimeOriginal` with fallback to upload time) and a "recently added" view (driven by upload time).
- Browser UX: login page, landing, month view, lightbox with caption editing and prev/next, drag-drop upload page.
- Login-walled access for `/mae/**` via a separate Spring Security filter chain using a single shared password; admin role inherited from the existing `/admin` chain for delete operations.
- A self-declared "uploader name" cookie on the upload form (no real per-contributor identity).
- WebDAV drop folder exposed by Milton at a new URL pattern with its own dedicated WebDAV user.
- Caddy host entry for the drop folder (e.g. `drop.davidneto.eu`).
- One-time `ApplicationRunner`-based bulk importer for the existing ~2200 photos.
- `docker-compose.yml`, `Caddyfile`, `Dockerfile`, `application.yml`, and `.env.example` updates.
- Tests covering ingest happy path, EXIF extraction, dedupe, security gating, drop-folder pickup.

**Out of scope (v1):**

- Face recognition or person/tag taxonomy.
- Albums or collections beyond year/month grouping.
- Comments, reactions, "likes".
- Caption edit history or undo (last write wins).
- Email, push, or RSS notifications when new items are added.
- Video transcoding (videos are stored and served as-is).
- Per-contributor accounts or attribution beyond the self-declared name field.
- Migration of any existing image data into `gallery_item`.

## Non-goals

- **Public discoverability.** The gallery is unlisted. `X-Robots-Tag: noindex, nofollow` is set on every `/mae` response and `Disallow: /mae` is added to `robots.txt`. Search-engine exclusion is not a security boundary, the login wall is.
- **WebDAV as a browseable archive.** WebDAV is write-only ingest. Once dropped files are picked up, they leave the WebDAV-visible folder and live under UUIDs. Users cannot browse or rearrange the gallery via WebDAV.
- **Strong contributor identity.** The `uploader_name` field is self-declared, optional, and editable. It is for human attribution in captions, not for authorization.

## Decisions Summary

| Decision | Choice |
|---|---|
| Auth model | Single shared password; self-declared "your name" field on upload (cookie-remembered). |
| Upload mechanisms | Both browser drag-drop AND WebDAV drop folder; same ingest service. |
| Date for year/month bucketing | EXIF `DateTimeOriginal` (or video container `creation_time`); fallback to `uploaded_at` when absent. |
| "Recently added" view | Separate top-of-page view, ordered by `uploaded_at` regardless of EXIF date. |
| Videos | Accepted as-is, no transcoding. Poster-frame extracted via `ffmpeg`. |
| Storage source-of-truth | Database. Files stored under UUIDs. WebDAV is a transient drop folder. |
| Permissions | Admin-only delete. Any logged-in contributor can edit captions. |
| Privacy posture | Login wall on everything (browse and upload both require the shared password). |
| URL path | `/mae`. |
| Bulk import of existing photos | One-time server-side `ApplicationRunner`, idempotent via content-hash dedupe. |
| Per-file size cap | Photos 50 MB; videos 500 MB; `multipart.max-file-size` raised to 500 MB. |
| Image variants | Thumbnail (~400 px) + display (~1600 px) + original; videos get a poster frame at both sizes. |

## Architecture

### Module Layout

```
com.davidneto.homepage.gallery
├── entity/
│   ├── GalleryItem.java              (one row per photo/video)
│   └── MediaKind.java                (enum: PHOTO, VIDEO)
├── repository/
│   └── GalleryItemRepository.java
├── service/
│   ├── GalleryStorage.java           (UUID paths, atomic writes, deletes)
│   ├── ExifExtractor.java            (DateTimeOriginal + orientation, via metadata-extractor)
│   ├── VideoMetadataExtractor.java   (ffprobe wrapper)
│   ├── ThumbnailGenerator.java       (photos via Thumbnailator)
│   ├── VideoPosterGenerator.java     (ffmpeg shell-out)
│   ├── GalleryIngestService.java     (the single ingest entrypoint)
│   ├── WebDavDropFolderScanner.java  (@Scheduled; picks up stable files)
│   └── BulkImporter.java             (one-time ApplicationRunner)
├── controller/
│   ├── GalleryController.java        (HTML pages: /mae, /mae/{y}/{m}, etc.)
│   └── GalleryApiController.java     (JSON: upload, edit caption, delete)
├── security/
│   └── GallerySecurityConfig.java    (separate filter chain for /mae/**)
└── config/
    └── GalleryProperties.java        (@ConfigurationProperties for app.gallery / app.mae)
```

The existing `SecurityConfig` filter chain is left as-is. `GallerySecurityConfig` registers a second `SecurityFilterChain` bean with `@Order` higher than the existing one, scoped via `securityMatcher("/mae/**")`. The two chains are independent.

### Database Schema (`V4__add_gallery.sql`)

```sql
CREATE TABLE gallery_item (
    id                  BIGSERIAL    PRIMARY KEY,
    media_kind          VARCHAR(10)  NOT NULL,           -- PHOTO | VIDEO
    storage_key         UUID         NOT NULL UNIQUE,    -- subdir = first 2 hex chars
    original_filename   VARCHAR(512) NOT NULL,
    content_type        VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    content_hash        CHAR(64)     NOT NULL UNIQUE,    -- sha-256, dedupe key
    width               INTEGER,                          -- nullable for video pre-probe
    height              INTEGER,
    duration_seconds    INTEGER,                          -- video only
    taken_at            TIMESTAMP,                        -- EXIF DateTimeOriginal (nullable)
    bucket_year         INTEGER      NOT NULL,           -- denormalized for cheap grouping
    bucket_month        INTEGER      NOT NULL,           -- 1..12
    bucket_source       VARCHAR(10)  NOT NULL,           -- EXIF | UPLOAD
    uploaded_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    uploader_name       VARCHAR(100),                     -- self-declared, nullable
    caption             TEXT,
    caption_updated_at  TIMESTAMP,
    caption_updated_by  VARCHAR(100)
);

CREATE INDEX idx_gallery_bucket ON gallery_item (bucket_year DESC, bucket_month DESC);
CREATE INDEX idx_gallery_recent ON gallery_item (uploaded_at DESC);
CREATE INDEX idx_gallery_hash   ON gallery_item (content_hash);
```

`bucket_year` / `bucket_month` are denormalized so the year/month archive is a fast index scan with no date-arithmetic in queries. `bucket_source` lets the UI show a small "(no EXIF date)" badge on items whose bucket is upload-time-derived.

### Filesystem Layout

Under `app.gallery.root-dir` (default `./gallery`; mounted as `/app/gallery` in Compose, backed by a new named volume `gallery_data`):

```
/app/gallery/
├── originals/<aa>/<uuid>.<ext>    (the bytes as uploaded; <ext> from sniffed type)
├── thumbs/<aa>/<uuid>.jpg         (~400 px wide, JPEG q80)
├── display/<aa>/<uuid>.jpg        (~1600 px wide, JPEG q85; video poster frame for VIDEO)
├── _tmp/<uuid>.part                (in-progress ingest scratch space)
└── _drop/                          (WebDAV-visible drop folder; emptied by ingest)
    └── _failed/                    (files the scanner could not ingest)
```

`<aa>` = first two hex chars of the UUID. Splits 2200+ files across 256 directories (~10 files per dir on average), keeping any single directory's fanout reasonable.

## Ingest Pipeline

`GalleryIngestService.ingest(InputStream src, String originalFilename, String declaredContentType, String uploaderName)`:

1. **Stream to scratch.** Write `src` to `/_tmp/<uuid>.part`, computing SHA-256 incrementally.
2. **Dedupe.** If `content_hash` already exists in `gallery_item`, delete the temp file and return the existing item. The same source file uploaded twice (or re-dropped, or re-imported by the bulk runner) is a no-op.
3. **Sniff content type.** Use magic-byte detection to determine the actual MIME (do not trust `declaredContentType` or filename extension). Allowlist:
   - Photos: `image/jpeg`, `image/png`, `image/heic`, `image/webp`.
   - Videos: `video/mp4`, `video/quicktime`.
   Anything else is rejected; temp file deleted.
4. **Extract metadata.**
   - Photos: `metadata-extractor` (Drew Noakes, pure Java, no native deps) reads EXIF `DateTimeOriginal`, orientation, and pixel dimensions.
   - Videos: shell out to `ffprobe -v quiet -print_format json -show_format -show_streams`. Parse `format.tags.creation_time`, primary video stream `width`/`height`, `format.duration`.
5. **Compute bucket.**
   - If a real EXIF/container date is present → `bucket_year`/`bucket_month` from it; `bucket_source = EXIF`; `taken_at` set to that timestamp.
   - Else → bucket from `uploaded_at`; `bucket_source = UPLOAD`; `taken_at = NULL`.
6. **Generate derivatives.**
   - Photo: `Thumbnailator` produces a 400 px thumb (JPEG q80) and a 1600 px display copy (JPEG q85). EXIF orientation is applied so derivatives render upright (the original is preserved untouched).
   - Video: `ffmpeg -ss 00:00:01 -i <src> -vframes 1 -vf scale=1600:-2 <display>.jpg`, then `Thumbnailator` resamples the poster frame to 400 px for the thumb. One ffmpeg invocation per video.
7. **Atomic finalize.** Move the temp file to `originals/<aa>/<uuid>.<ext>` with `Files.move(..., ATOMIC_MOVE)`, write the thumb and display files, then `INSERT` the row in a transaction. On failure at any step, partially written files are unlinked and the temp file deleted.

The same service is invoked from three callers:

- `GalleryApiController.upload()` — handles a multipart `POST /mae/api/items`. Iterates parts and calls `ingest` per file. Returns a JSON array of created item summaries (including dedupe hits, marked as such).
- `BulkImporter.importDirectory(Path)` — the `ApplicationRunner` activated by `--spring.profiles.active=bulkimport`. Walks the configured tree (`--gallery.import.path=...`), calls `ingest` for each file, prints a progress line per 50 items, and exits when done. Idempotent via dedupe.
- `WebDavDropFolderScanner` — `@Scheduled(fixedDelay = 30s)`. For each file in `_drop/`:
  - Skip if size has changed since the previous scan (file still being uploaded).
  - Otherwise call `ingest`, then delete the source file.
  - On `ingest` failure, move the source to `_drop/_failed/<original-name>` and write a sibling `<original-name>.error` with the exception message.
  Subdirectories under `_drop/` are walked recursively; original folder structure is **not** preserved (year/month is computed from EXIF, not file location).

### WebDAV Drop Folder Wiring

Add a second Milton `FilterRegistrationBean` at URL pattern `/gallery-drop/*`, backed by a `FileSystemResourceFactory` rooted at `_drop/`. It uses its own `SecurityManager` that authenticates only against a single dedicated WebDAV user (`mae-drop`, password from `WEBDAV_DROP_PASSWORD` env var, BCrypt-hashed at startup). This user is **not** stored in the `webdav_users` table — it lives in config only, kept fully separate from the per-user WebDAV trees served at `/webdav/*`.

`Caddyfile` adds:

```
drop.davidneto.eu {
    rewrite * /gallery-drop{uri}
    reverse_proxy app:8080 { ... same headers as cloud ... }
}
```

`SecurityConfig.filterChain` adds `/gallery-drop/**` to the list of `permitAll()` paths and to the CSRF ignore list, mirroring how `/webdav/**` is handled today.

## Auth, Sessions, Permissions

- **Filter chain.** `GallerySecurityConfig` registers a `SecurityFilterChain` bean with `@Order(1)`, scoped via `securityMatcher("/mae/**")`. The existing `SecurityConfig.filterChain` bean is given an explicit `@Order(2)` in the same change so Spring resolves the two chains deterministically (without explicit ordering on both, registering a second chain throws on startup).
- **Login.** `GET /mae/login` shows a form with a single password field (no username). `POST /mae/login` accepts either the shared family password (`app.mae.password`, env `MAE_PASSWORD`) or the admin password (`app.admin.password`). Both compared with constant-time BCrypt against values hashed at startup. The match determines the role granted:
  - Family password → principal `family`, role `ROLE_GALLERY_CONTRIBUTOR`.
  - Admin password → principal `admin`, roles `ROLE_GALLERY_CONTRIBUTOR` and `ROLE_GALLERY_ADMIN`.
  Wrong password → standard auth failure (rate-limiter handles it).
- **Session.** Spring's default session cookie, scoped to `/mae`, `Secure`, `HttpOnly`, `SameSite=Lax`, with a 30-day rolling expiry so relatives don't re-auth constantly. Independent of any `/admin` session (different cookie path).
- **Rate-limiting.** The existing `LoginRateLimiter` and its filter are reused: the `/mae/login` form-login is wired with the same `RateLimitAuthenticationSuccessHandler` and `RateLimitAuthenticationFailureHandler`, and `LoginRateLimitFilter` is added to the gallery chain. Per-IP and per-username buckets are shared with the admin and WebDAV realms.
- **Admin gating on delete.** `DELETE /mae/api/items/{id}` is protected with `@PreAuthorize("hasRole('GALLERY_ADMIN')")` (or the equivalent `authorizeHttpRequests` matcher). No separate cross-cookie check is needed — a single `/mae` session created with the admin password carries the role.
- **Contributor name (no auth role).** After login, the upload page reads a `mae_contributor` cookie (1 year, `Secure`, `HttpOnly=false` so client-side JS can pre-fill the form). Editing the input updates the cookie. Empty is allowed — upload row is stored with `uploader_name = NULL`.
- **CSRF.** Enabled (Spring default cookie token) for everything under `/mae/**` except `/gallery-drop/**`.

### Permission Matrix

| Action | Required |
|---|---|
| `GET /mae/**` HTML pages | Authenticated `family` session |
| `GET /mae/media/{thumb|display|original}/{id}` | Authenticated `family` session |
| `POST /mae/api/items` (upload) | Authenticated `family` session |
| `PATCH /mae/api/items/{id}` (edit caption) | Authenticated `family` session |
| `DELETE /mae/api/items/{id}` | Authenticated `/mae` session with `ROLE_GALLERY_ADMIN` (granted by logging into `/mae/login` with the admin password) |
| `PUT /gallery-drop/...` (WebDAV) | Basic Auth as `mae-drop` user |
| `BulkImporter` | CLI / profile-activated; runs as the JVM user, not gated by HTTP auth |

Bulk-imported items and drop-folder ingests are stored with `uploader_name = NULL` by default. The bulk importer accepts an optional `--gallery.import.uploader=David` flag to attribute the batch.

## URL Surface

| URL | Purpose |
|---|---|
| `GET /mae` | Landing: "Recently added" strip + year/month archive index |
| `GET /mae/login`, `POST /mae/login` | Form login (shared password) |
| `POST /mae/logout` | Logout |
| `GET /mae/recent` | Paginated "recently added" feed |
| `GET /mae/{year}` | Year overview: months with item counts and a cover thumb |
| `GET /mae/{year}/{month}` | Month grid, items by `taken_at` ascending |
| `GET /mae/item/{id}` | Lightbox: display image / `<video>` + caption + metadata |
| `GET /mae/upload` | Drag-drop upload page |
| `POST /mae/api/items` | Multipart upload (multiple `file` parts + optional `uploaderName` and `caption` form fields applied to the whole batch); returns JSON array of created items |
| `PATCH /mae/api/items/{id}` | Update caption (JSON body) |
| `DELETE /mae/api/items/{id}` | Admin-only delete |
| `GET /mae/media/thumb/{id}` | Thumbnail bytes (long cache: `Cache-Control: private, max-age=31536000, immutable`) |
| `GET /mae/media/display/{id}` | Display-size bytes (long cache, same headers) |
| `GET /mae/media/original/{id}` | Original bytes; `Content-Disposition: attachment; filename="<original_filename>"` |

All `/mae/**` responses set `X-Robots-Tag: noindex, nofollow`. The app's `robots.txt` adds `Disallow: /mae`.

## UX

### Aesthetic

The main site uses a terminal aesthetic; the gallery deliberately does not. Calm, image-forward, generous whitespace. New `templates/mae/` directory and `static/css/mae.css` keep the gallery visually self-contained.

### Pages

- **Landing (`/mae`).** Quiet header with `app.mae.title` (default: "In memory of …"). "Recently added" horizontal strip of 6–12 most recent thumbs (each linking to `/mae/item/{id}`); "see all →" links to `/mae/recent`. Below: reverse-chronological list of years, each expanding to months with a 6-thumb preview grid and a "more →" link to the month view. Most recent year auto-expanded; others collapsed. Persistent "Add photos/videos" CTA.
- **Month view (`/mae/{year}/{month}`).** Responsive square-ish thumbnail grid (CSS grid `auto-fill` `minmax(160px, 1fr)`). Items chronological within the month (`taken_at` ascending; UPLOAD-bucketed items grouped at the end of their month, ordered by `uploaded_at`). Click → lightbox.
- **Lightbox (`/mae/item/{id}`).** Display-size image, or `<video controls preload="metadata">` for video. Caption underneath; "edit" toggles to a textarea + save button (PATCH). Metadata line: uploader name (if any), upload date, EXIF date (if present), original filename. Prev/next arrows scoped to the current month, with keyboard ←/→ and `Esc` to return. "Download original" link. Admin-only delete button with confirm.
- **Upload (`/mae/upload`).** Drag-drop area accepting multiple files. Per-file progress bars; concurrency capped at 3 parallel uploads. "Your name" input pre-filled from `mae_contributor` cookie; editing updates the cookie. Optional shared caption applied to the whole batch. Files exceeding size cap or with disallowed MIME flagged client-side before POST. Successful uploads link to their lightbox; dedupe-hits are shown with a "(already in gallery)" note linking to the existing item.

## Operational Setup

### Configuration (`application.yml`)

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB

app:
  mae:
    password: ${MAE_PASSWORD}
    title: ${MAE_TITLE:In memory of}
  gallery:
    root-dir: ${GALLERY_ROOT_DIR:./gallery}
    drop:
      username: ${WEBDAV_DROP_USERNAME:mae-drop}
      password: ${WEBDAV_DROP_PASSWORD}
      scan-interval-seconds: 30
      stable-after-seconds: 10
```

### `.env.example` additions

```
MAE_PASSWORD=changeme
MAE_TITLE=In memory of <name>
GALLERY_ROOT_DIR=/app/gallery
WEBDAV_DROP_USERNAME=mae-drop
WEBDAV_DROP_PASSWORD=changeme
```

### `docker-compose.yml`

Add a named volume `gallery_data` and mount it at `/app/gallery` in the `app` service. Add the `MAE_*`, `GALLERY_*`, and `WEBDAV_DROP_*` env vars.

### `Dockerfile`

Install `ffmpeg` in the runtime stage (`apk add --no-cache ffmpeg`, ~30 MB). Pulls in `ffprobe`.

### `Caddyfile`

Add a host block for `drop.davidneto.eu` proxying to `app:8080` with a `rewrite * /gallery-drop{uri}` (mirror of the existing `cloud.davidneto.eu` block).

### Bulk Import

```
docker compose run --rm \
  -v /host/path/to/photos:/import:ro \
  app java -jar /app/app.jar \
  --spring.profiles.active=bulkimport \
  --gallery.import.path=/import \
  --gallery.import.uploader=David
```

`BulkImporter` is an `ApplicationRunner` only registered under the `bulkimport` profile. It walks the tree, calls `GalleryIngestService.ingest` per file, prints progress every 50 items, and exits on completion. Safe to re-run — dedupe makes it a no-op for already-imported files.

## Testing

**Unit tests:**
- `ExifExtractor`: known-EXIF JPEG returns expected `DateTimeOriginal` and orientation; stripped JPEG returns empty.
- `GalleryIngestService` bucket logic: EXIF present → `bucket_source = EXIF`; EXIF absent → `bucket_source = UPLOAD` with bucket from `uploaded_at`.
- `ThumbnailGenerator`: orientation-rotated source produces upright thumb; output dimensions within ±1 px of target.
- Content-type sniffing: rejects `text/plain` renamed to `.jpg`.

**Integration tests** (Spring Boot test slice, H2 + tmp dir):
- Full ingest happy path: POST a real JPEG, assert row exists, files written under `originals/`, `thumbs/`, `display/`, dedupe of identical second POST returns existing item.
- `WebDavDropFolderScanner` picks up a stable file and ingests it; an in-progress (size-changing) file is skipped.
- Drop-folder ingest failure moves the source under `_failed/` with a `.error` sibling.

**Security tests:**
- `GET /mae` and `GET /mae/recent` redirect to `/mae/login` when unauthenticated.
- `POST /mae/api/items` rejected (401/403) without session.
- `DELETE /mae/api/items/{id}` rejected (403) for a session created with the family password; accepted for a session created with the admin password.
- `/gallery-drop/...` PUT rejected without `mae-drop` Basic Auth.
- `LoginRateLimiter` rejects after configured failures on `/mae/login`.

## File Manifest

New files:

- `src/main/resources/db/migration/V4__add_gallery.sql`
- `src/main/java/com/davidneto/homepage/gallery/entity/{GalleryItem,MediaKind}.java`
- `src/main/java/com/davidneto/homepage/gallery/repository/GalleryItemRepository.java`
- `src/main/java/com/davidneto/homepage/gallery/service/{GalleryStorage,ExifExtractor,VideoMetadataExtractor,ThumbnailGenerator,VideoPosterGenerator,GalleryIngestService,WebDavDropFolderScanner,BulkImporter}.java`
- `src/main/java/com/davidneto/homepage/gallery/controller/{GalleryController,GalleryApiController}.java`
- `src/main/java/com/davidneto/homepage/gallery/security/{GallerySecurityConfig,GalleryUserDetailsService,GalleryDropSecurityManager}.java`
- `src/main/java/com/davidneto/homepage/gallery/config/GalleryProperties.java`
- `src/main/resources/templates/mae/{layout,landing,month,lightbox,upload,login}.html`
- `src/main/resources/static/css/mae.css`
- `src/main/resources/static/js/mae.js`
- `src/test/java/com/davidneto/homepage/gallery/...` (unit + integration tests)

Modified files:

- `pom.xml` — add `metadata-extractor`, `thumbnailator`.
- `application.yml` — multipart limits, `app.mae.*`, `app.gallery.*`.
- `.env.example`, `docker-compose.yml`, `Caddyfile`, `Dockerfile`.
- `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java` — add second filter registration for `/gallery-drop/*`.
- `src/main/java/com/davidneto/homepage/config/SecurityConfig.java` — `permitAll()` and CSRF ignore for `/gallery-drop/**`; add explicit `@Order(2)` to `filterChain` bean so it can coexist with the `/mae/**` chain.
- `src/main/resources/static/robots.txt` (create if absent) — `Disallow: /mae`.
