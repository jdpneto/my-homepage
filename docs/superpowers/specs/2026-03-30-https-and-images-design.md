# HTTPS & Blog/Page Image Uploads — Design Specification

## Overview

Two enhancements to the personal homepage:

1. **HTTPS support** via Caddy's built-in self-signed certificates (`tls internal`), with documented steps for switching to a real Let's Encrypt certificate when a domain is added.
2. **Image uploads** for blog posts and static pages, with a per-entity image gallery in the Markdown editor that supports click-to-copy for easy embedding.

## Feature 1: HTTPS Configuration

### Caddyfile Changes

Generate a self-signed certificate with openssl and mount it into the Caddy container:

```bash
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -keyout certs/key.pem -out certs/cert.pem \
  -days 365 -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

Caddyfile configuration:

```
:443 {
    tls /etc/caddy/certs/cert.pem /etc/caddy/certs/key.pem
    reverse_proxy app:8080
}

:80 {
    redir https://{host}{uri} permanent
}
```

Docker Compose mounts the certs directory into the Caddy container as read-only:

```yaml
volumes:
  - ./certs:/etc/caddy/certs:ro
```

Note: Caddy's `tls internal` was not used because it generates a CA certificate that browsers hard-reject with no bypass option. A standard openssl self-signed leaf certificate allows browsers to show the "Accept the Risk" prompt.

- `:443` with mounted cert/key files — browsers show a bypassable security warning
- `:80` redirects all HTTP traffic to HTTPS permanently
- `certs/` is in `.gitignore` — private keys are not committed

### Switching to a Real Certificate (Future)

When a domain is available:

1. Point the domain's A record to the VPS IP address
2. Replace the entire Caddyfile with:
   ```
   yourdomain.com {
       reverse_proxy app:8080
   }
   ```
   Remove the `tls` directive, the `:80` redirect block, and the `certs` volume mount — Caddy handles automatic HTTPS and HTTP→HTTPS redirection when given a real domain name.
3. Run `docker compose restart caddy`
4. Caddy automatically provisions a Let's Encrypt certificate and handles renewal

## Feature 2: Image Uploads

### Data Model

New `image` table (Flyway migration):

| Column       | Type         | Notes                                           |
|-------------|-------------|--------------------------------------------------|
| id          | BIGINT PK    | Auto-generated                                   |
| owner_type  | VARCHAR(20)  | `BLOG_POST` or `STATIC_PAGE`                    |
| owner_id    | BIGINT       | ID of the owning entity                          |
| filename    | VARCHAR(255) | Original filename (sanitized)                    |
| stored_name | VARCHAR(255) | UUID-based name on disk (avoids collisions)      |
| content_type| VARCHAR(100) | MIME type (e.g. `image/png`)                     |
| size        | BIGINT       | File size in bytes                               |
| created_at  | TIMESTAMP    | Set on upload                                    |

**Index:** Composite on `(owner_type, owner_id)` for fast gallery lookups.

**Allowed formats:** `.jpg`, `.jpeg`, `.png`, `.gif`, `.webp` (same as existing profile photo validation).

### File Storage

Files stored at: `uploads/images/{owner_type}/{owner_id}/{stored_name}`

Uses the existing `uploads` named Docker volume. The existing WebConfig resource handler at `/uploads/**` already serves these files publicly.

### Backend — ImageService

**Methods:**
- `upload(ownerType, ownerId, MultipartFile)` — validates format/size, generates UUID stored name, writes to disk, saves `Image` record
- `listByOwner(ownerType, ownerId)` — returns all images for a given entity
- `delete(imageId)` — removes file from disk and deletes DB record
- `deleteAllByOwner(ownerType, ownerId)` — cascade deletion of all images for an entity

**Max file size:** 5MB (configurable via `spring.servlet.multipart.max-file-size`).

### REST API

JSON endpoints used by the editor JavaScript:

| Method | Route                                          | Description              |
|--------|-------------------------------------------------|--------------------------|
| POST   | `/admin/api/images`                             | Upload image (multipart) |
| GET    | `/admin/api/images?ownerType={t}&ownerId={id}` | List images for entity   |
| DELETE | `/admin/api/images/{id}`                        | Delete image             |

**POST params:** `ownerType`, `ownerId`, `file` (multipart).

**Response format:** JSON with `id`, `filename`, `url` (e.g. `/uploads/images/BLOG_POST/3/abc-123.png`), `createdAt`.

All endpoints require authentication (under `/admin/**`). CSRF token included in requests from the editor JavaScript.

### Frontend — Image Gallery in Editor

**Upload button:** Styled button ("Upload Image") placed below the EasyMDE editor, consistent with the terminal theme. Available in both blog post and static page editors.

**Upload flow:**
1. Click button opens file picker
2. Select image → JavaScript sends POST to `/admin/api/images` with owner type and id
3. On success, image appears in the gallery

**Image gallery:** Displayed below the upload button as a grid of thumbnails.
- Each thumbnail shows a small preview of the image
- Click a thumbnail → copies `![filename](/uploads/images/...)` to the clipboard
- Brief visual feedback on copy (e.g. "Copied!" tooltip)
- Delete button (small X) on each thumbnail → confirmation prompt, then DELETE request removes it

**New entity edge case:** The upload button and gallery are hidden/disabled until the post or page has been saved at least once. This avoids orphan images — every image always has a valid owner.

### Cascade Deletion

When a blog post or static page is deleted:
- All associated `Image` records are deleted from the database
- All associated image files are deleted from disk
- Handled in `BlogPostService` and `StaticPageService` delete flows by calling `ImageService.deleteAllByOwner(ownerType, ownerId)`

No orphan cleanup job is needed since images always require a saved owner entity.
