# Rate Limiter & WebDAV Manual Smoke Test

## Prereqs
- `docker compose up -d --build` after pulling the branch.
- One WebDAV user created via `/admin/webdav-users`, e.g. `alice` / `alicepass1`.

## Admin login rate limit
1. Browse to `https://davidneto.eu/admin/login`.
2. Submit wrong password 5 times — each redirects back with `?error`.
3. Submit again — response is `429 Too Many Requests`, body says "Too many attempts", `Retry-After` header present.
4. Wait 60 s; submit correct credentials — succeeds.

## WebDAV auth rate limit
1. Attempt WebDAV auth with wrong password 5 times via `curl`:

   ```sh
   for i in $(seq 1 6); do
     curl -u alice:wrong -X PROPFIND -H "Depth: 0" -i https://cloud.davidneto.eu/ | head -5
   done
   ```

2. On the 6th attempt the response is 401 with a `Retry-After` header.
3. Use correct credentials — 207 Multi-Status.

## WebDAV file transfer is not rate-limited
1. With correct credentials, `rclone copy` a 20 MB file to `cloud.davidneto.eu/`.
2. Observe many PUT / PROPFIND requests; none return 401 / 429.

## Per-user isolation
1. Create second user `bob`.
2. As `alice`, PUT `/secret.txt`.
3. As `bob`, GET `/secret.txt` → 404.
4. On the host: `ls /var/lib/docker/volumes/my-homepage_webdav_data/_data/` shows `alice/` and `bob/`.
