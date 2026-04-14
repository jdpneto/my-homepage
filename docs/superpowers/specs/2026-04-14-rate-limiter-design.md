# Login Rate Limiter — Design Specification

## Overview

Add a rate limiter to both login flows in the project: the homepage admin login (`/admin/login`) and the WebDAV Basic Auth at `cloud.davidneto.eu`. The limiter defends against credential stuffing / brute-force password guessing (per-username lockout) and generic login-endpoint abuse (per-IP window).

As part of this work, the current `bytemark/webdav` Docker container is replaced by a Milton-based WebDAV server embedded in the existing Spring Boot app, so a single in-memory rate limiter can be shared between the two realms. WebDAV gains multi-user support with per-user isolated directories, managed from the admin panel.

## Scope

**In scope:**

- `LoginRateLimiter` in-memory service with per-IP sliding window and per-username escalating lockout.
- Integration with Spring Security form-login (admin) and Milton `SecurityManager` (WebDAV).
- Replacement of `bytemark/webdav` by `milton-server-ce` mounted at `/webdav/*` in the Spring Boot app.
- `webdav_users` database table with Flyway migration.
- Admin UI pages to create, list, reset-password, and delete WebDAV users.
- `docker-compose.yml`, `Caddyfile`, and `Dockerfile` updates for the new topology.

**Out of scope:**

- Persisting rate-limit state across app restarts (accepted trade-off; see *Non-goals*).
- Redis or any shared cache for multi-replica deployment.
- Changes to the admin user model — admin stays env-var / in-memory via `InMemoryUserDetailsManager` as today.
- Migrating existing data from the old `webdav_data` volume (existing data is disposable per user decision).
- Caddy-level rate limiting.

## Non-goals

- **Horizontal scale.** The limiter is per-process. The site runs one app container; adding replicas would require Redis.
- **Survival across restarts.** Counters reset on app restart. Accepted because restarts are rare; the username lockout still slows attackers within a window.
- **Global DoS protection.** This is a login-flow rate limiter, not an edge WAF.

## Architecture

### Topology

```
docker-compose.yml
├── caddy        (reverse proxy, 80/443)
├── app          (Spring Boot — homepage + WebDAV + rate limiter)
└── postgres     (PostgreSQL 16)

volumes:
├── pgdata       (Postgres data)
├── uploads      (site photo uploads)
├── webdav_data  (WebDAV file storage, mounted at /app/webdav in `app`)
└── caddy_data, caddy_config
```

The `webdav` service is **removed** from `docker-compose.yml`. WebDAV is served from the same Spring Boot JVM as the homepage.

### Caddy routing

```
davidneto.eu, www.davidneto.eu {
    reverse_proxy app:8080
}

cloud.davidneto.eu {
    reverse_proxy app:8080 {
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
    }
    # Rewrite so clients see cloud.davidneto.eu/foo.txt
    # and the app sees /webdav/foo.txt
    rewrite * /webdav{uri}
}
```

### Two auth realms in one app

- `/admin/**` — Spring Security form login, in-memory admin user (unchanged).
- `/webdav/**` — Milton's Basic Auth, DB-backed `WebDavUser` table.

Both call into the same `LoginRateLimiter` bean.

### Shared rate-limiter flow

```
Request
  │
  ▼
RateLimitFilter / Milton SecurityManager
  │  check(ip, username)
  │
  ├── ALLOW     → proceed to auth
  ├── BLOCK_IP  → 429 (admin) / 401 with Retry-After (webdav)
  └── BLOCK_USER→ same
        │
Auth outcome
  ├── success → recordSuccess(username)
  └── failure → recordFailure(ip, username)
```

## Components

### `LoginRateLimiter` (Spring `@Component`)

Single source of truth for rate-limit state. Holds two in-memory maps.

**State:**

```java
ConcurrentHashMap<String, IpWindow>       ipAttempts;    // key = client IP
ConcurrentHashMap<String, UserFailCounter> userAttempts; // key = username (lowercased)
```

- `IpWindow` — holds a deque of failure timestamps; on each check, drops entries older than the configured window.
- `UserFailCounter` — `int consecutiveFailures`, `Instant lockedUntil`, `Instant lastFailure`.

**Capacity guard:** both maps capped (default 10 000 entries). On insert, if over cap, evict the oldest-touched entry. Prevents OOM from spray attacks.

**Scheduled sweep** (`@Scheduled(fixedDelay = 10 minutes)`): removes entries whose IP window is empty and whose username counter has `lockedUntil` in the past and last failure older than 1 hour.

**Public API:**

```java
enum DecisionKind { ALLOW, BLOCK_IP, BLOCK_USER }

record Decision(DecisionKind kind, long retryAfterSeconds) {}

Decision check(String clientIp, String username);
void recordFailure(String clientIp, String username);
void recordSuccess(String username);
```

**Behavior:**

- `check` first evaluates the IP window — if failures in the last `ipWindowSeconds` ≥ `ipMaxFailures`, returns `BLOCK_IP` with `retryAfter = window_remaining`.
- Then evaluates the username counter — if `lockedUntil` is in the future, returns `BLOCK_USER`.
- Else returns `ALLOW`.
- `recordFailure` appends to the IP window, increments the username counter, and sets `lockedUntil` if thresholds crossed.
- `recordSuccess` clears the username counter.

**Username normalization:** lower-cased + trimmed before keying the map. Consistent with how usernames flow through Spring Security and Milton.

### Homepage login integration

- `LoginRateLimitFilter` — runs before `UsernamePasswordAuthenticationFilter` in the Spring Security chain. Matches `POST /admin/login` only. Reads `username` form param and client IP. On `BLOCK_*` returns 429 with `Retry-After`, rendering the login page with an error flash.
- `RateLimitAuthenticationFailureHandler` — custom `AuthenticationFailureHandler` calls `recordFailure` and delegates to `SimpleUrlAuthenticationFailureHandler("/admin/login?error")`.
- `RateLimitAuthenticationSuccessHandler` — calls `recordSuccess` and delegates to the default success URL.

Wired via `SecurityConfig.filterChain`.

### WebDAV integration (Milton)

**Dependency** (add to `pom.xml`):

```xml
<dependency>
  <groupId>io.milton</groupId>
  <artifactId>milton-server-ce</artifactId>
  <version><!-- latest stable, pinned during implementation --></version>
</dependency>
```

**Servlet registration** — a `ServletRegistrationBean<MiltonServlet>` mounts Milton at `/webdav/*`.

**`WebDavSecurityManager`** implements `io.milton.http.SecurityManager`.

```java
Object authenticate(String user, String password):
    ip = RequestContext.current().getClientIp(); // from Milton request
    decision = rateLimiter.check(ip, user);
    if (decision.kind != ALLOW) {
        setRetryAfterHeader(decision.retryAfterSeconds);
        return null;                             // Milton returns 401
    }
    WebDavUser u = repo.findByUsername(normalize(user)).orElse(null);
    if (u == null || !passwordEncoder.matches(password, u.getPasswordHash())) {
        rateLimiter.recordFailure(ip, user);
        return null;
    }
    rateLimiter.recordSuccess(user);
    return new WebDavPrincipal(u.getUsername());
```

**`PerUserFileResourceFactory`** — wraps Milton's `FileSystemResourceFactory`. On each request, reads the authenticated principal from the request context and scopes the root directory to `<webdav.root-dir>/<username>`. Directory auto-created on first successful auth if absent.

**Spring Security config** — `/webdav/**` is excluded from the Spring Security form-login filter chain; CSRF is disabled for that path. The `LoginRateLimitFilter` is scoped to `/admin/login` only, so it does not fire on WebDAV requests (the limiter is consulted from inside the Milton security manager).

### Data model

**Flyway migration `V3__add_webdav_users.sql`:**

```sql
CREATE TABLE webdav_users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

**Entity:** `WebDavUser(id, username, passwordHash, createdAt)`.

**Repository:** `WebDavUserRepository extends JpaRepository<WebDavUser, Long>` with `Optional<WebDavUser> findByUsername(String username)`.

**Service:** `WebDavUserService` — `create(username, rawPassword)`, `delete(id)`, `resetPassword(id, rawPassword)`, `list()`. Uses the existing `PasswordEncoder` (BCrypt) bean.

### Admin UI

New pages in `/admin`:

| Route                                  | Method | Purpose                    |
|----------------------------------------|--------|----------------------------|
| `/admin/webdav-users`                  | GET    | List users (username, created_at, delete button, reset-password button). |
| `/admin/webdav-users`                  | POST   | Create user (username, password). |
| `/admin/webdav-users/{id}/reset-password` | POST   | Set a new password.        |
| `/admin/webdav-users/{id}/delete`      | POST   | Delete user. Files on disk are **not** deleted; a confirmation message states this. |

Added to the admin navigation alongside Posts, Pages, Social Links, Settings. Thymeleaf templates follow the existing admin page style.

**Input validation:**

- Username: regex `^[a-zA-Z0-9_-]{1,64}$`. Rejected on create and re-checked on auth (defense in depth against path traversal via directory name).
- Password on create / reset: minimum 8 characters.

## Data flow

### Homepage login (success)

```
Browser POST /admin/login (username, password, CSRF)
  → LoginRateLimitFilter.check → ALLOW
  → UsernamePasswordAuthenticationFilter → success
  → RateLimitAuthenticationSuccessHandler.recordSuccess
  → redirect /admin/posts
```

### Homepage login (rate-limited)

```
Browser POST /admin/login
  → LoginRateLimitFilter.check → BLOCK_IP or BLOCK_USER
  → 429 + Retry-After, login page re-rendered with error
```

### WebDAV auth (success)

```
Client PROPFIND /webdav/
  → Milton SecurityManager.authenticate
       rateLimiter.check → ALLOW
       WebDavUser lookup + BCrypt verify → ok
       rateLimiter.recordSuccess
  → PerUserFileResourceFactory scopes to <root>/<user>/
  → 207 Multi-Status
```

### WebDAV auth (rate-limited)

```
Client PROPFIND /webdav/
  → Milton SecurityManager.authenticate
       rateLimiter.check → BLOCK_*
       response: 401 + WWW-Authenticate + Retry-After
```

## Configuration

`application.yml`:

```yaml
app:
  webdav:
    root-dir: ${WEBDAV_ROOT_DIR:./webdav}
  rate-limit:
    ip-max-failures: 5
    ip-window-seconds: 60
    user-lockout-threshold: 5
    user-lockout-short-seconds: 300
    user-lockout-long-threshold: 10
    user-lockout-long-seconds: 1800
    max-entries: 10000
    sweep-interval-seconds: 600
```

`server.forward-headers-strategy: NATIVE` so `HttpServletRequest.getRemoteAddr()` returns the real client IP from `X-Forwarded-For` that Caddy sets.

## Docker / infrastructure changes

### `docker-compose.yml`

- **Remove** the `webdav` service block.
- **Remove** `WEBDAV_USERNAME`, `WEBDAV_HASH`, `WEBDAV_PASSWORD` from the `caddy` and `webdav` environment sections.
- **Add** to `app`:
  - `volumes: - webdav_data:/app/webdav`
  - `environment: - WEBDAV_ROOT_DIR=/app/webdav`
- Keep the `webdav_data` named volume declaration (will be re-created empty).

### `Dockerfile`

- Add `RUN mkdir -p /app/webdav` alongside the existing uploads dir.

### `Caddyfile`

- Change `cloud.davidneto.eu` to reverse-proxy `app:8080` with a path rewrite to `/webdav/*`, instead of reverse-proxying `webdav:80`.

## Error handling

- **Admin login, rate-limited:** HTTP **429**, `Retry-After: <seconds>`. Login page re-rendered with a neutral message: *"Too many attempts. Try again in N seconds."* or *"This account is temporarily locked. Try again in N minutes."* — never reveals whether the username exists.
- **Admin login, bad credentials (not rate-limited):** standard `redirect:/admin/login?error` via Spring Security.
- **WebDAV, rate-limited:** HTTP **401** (not 429) with `WWW-Authenticate: Basic realm="webdav"` and `Retry-After: <seconds>`. 401 is chosen because common WebDAV clients (Finder, Explorer, `rclone`) handle 401 cleanly; 429 is less uniformly supported.
- **WebDAV, bad credentials (not rate-limited):** HTTP 401 + `WWW-Authenticate`.
- **Input validation errors (admin UI):** re-render the form with an inline error message, no redirect.
- **Map capacity exhaustion:** LRU-evict oldest entry; logged at `INFO` once per sweep if an eviction occurred.

## Logging

- Failed auth attempt → `WARN` with fields: `ip`, `username`, `rateLimited (boolean)`, `realm (admin|webdav)`.
- Successful auth → `INFO` with `ip`, `username`, `realm`.
- Never log passwords or authorization headers.

## Testing

### Unit tests (no Spring context)

`LoginRateLimiterTest`:

- IP window: 5 failures → 6th call returns `BLOCK_IP`; advancing a mocked clock past the window allows again.
- Username lockout: 5 consecutive failures → `BLOCK_USER` with `retryAfter ≈ 300s`; 10 failures → `retryAfter ≈ 1800s`.
- `recordSuccess` clears the username counter.
- Concurrency: 100 `recordFailure` calls across threads → final count equals 100.
- Eviction: inserting beyond `max-entries` evicts the oldest entry.

### Integration tests (`@SpringBootTest` with H2 test profile)

`AdminLoginRateLimitIT`:

- 5 wrong-password POSTs to `/admin/login` from the same IP → 6th returns 429 with `Retry-After`.
- Correct password after 4 wrong attempts → succeeds, counter cleared.
- Two distinct `X-Forwarded-For` values don't share counters.

`WebDavAuthRateLimitIT`:

- 5 `PROPFIND` requests with wrong password → 6th still 401 but includes `Retry-After`.
- Correct Basic Auth after lockout window expires → succeeds.
- Successful `PUT /webdav/foo.txt` as user `alice` writes to `<root>/alice/foo.txt` and is invisible to `bob`.

`WebDavUserAdminIT`:

- Create user via admin UI → appears in list; can authenticate against WebDAV.
- Delete user → can no longer authenticate; files remain on disk.
- Reset password → old password fails, new password works.
- Unauthenticated request to `/admin/webdav-users` redirects to `/admin/login`.

### Manual smoke test (documented, not automated)

- `rclone` or macOS Finder mounts `https://cloud.davidneto.eu`, creates a file, disconnects, re-connects, sees the file.
- Transfer a 10 MB+ file: verify the request burst is **not** rate-limited (we count auth failures, not request volume).

## Migration notes

- Existing `webdav_data` volume contents are discarded per explicit user decision. Remove and recreate the volume on deploy.
- Existing `WEBDAV_USERNAME` / `WEBDAV_HASH` / `WEBDAV_PASSWORD` env vars are no longer read. Remove from `.env` to avoid confusion. New WebDAV users are created through the admin UI after first deploy.
- No data migration for the homepage/admin side.

## Open questions

None.
