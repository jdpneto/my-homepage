# Home-screen app + "Remember this device" — design (short)

Approved by David on 2026-11-21 (option A, 90 days). Replaces the planned native/Flutter app: the phone page plus ntfy is enough.

## Intent

Open the camera page from the phone's home screen like an app and stay signed in — today the admin session ends after 30 min idle (Tomcat default) and on every deploy (in-memory sessions), and login always lands on Posts.

## Goals

- **Home-screen app:** public `/cameras.webmanifest` (`name` "Cameras", `short_name` "Cameras", `start_url` `/admin/cameras`, `scope` `/`, `display` `standalone`, dark `background_color`/`theme_color` matching the page), icons 192/512 PNG + `apple-touch-icon` 180 PNG (simple camera glyph on the page's dark background), linked from `admin/cameras.html` and `admin/login.html` with `apple-mobile-web-app-capable`/`mobile-web-app-capable` and `theme-color` metas.
- **Remember this device:** checkbox on `/admin/login` (checked by default on the login form; label "Remember this device for 90 days"). When checked, a remember-me cookie keeps the admin signed in for **90 days after the last use** (rolling), surviving deploys and restarts.
- **Land where you were going:** after login go to the originally requested page (default `/admin/posts`).
- **Revocation:** the cameras page button becomes "Sign out all devices": revokes app bearer tokens **and** every remembered device; logout forgets this device.

## Decided (my calls)

- Token: own `RememberMeServices` (extends `AbstractRememberMeServices`), **non-rotating** — cookie `series:token` (each 32 random bytes, base64url), DB table `admin_remember_token(series PK, token_hash SHA-256 hex, username, created_at, last_used_at)` via Flyway `V8`. Constant-time hash compare; expired (`last_used_at` + 90 d < now) or unknown → cookie cleared, row deleted. On each auto-login: `last_used_at` = now (at most once per hour to limit writes) and cookie re-issued with max-age 90 d. *Why not Spring's persistent-token service:* it rotates the token on every auto-login, and parallel requests from the phone trip its theft detection and sign the user out everywhere. *Why not the hash-based one:* the admin password hash is regenerated at each start, so cookies would die on every deploy.
- Cookie: `HttpOnly`, `Secure` (except plain-HTTP tests), `SameSite=Lax`, path `/`, name `remember-admin`.
- Applies to the admin chain and the camera API chain (so an API call after the session expired auto-logs in instead of showing "Signed out"). Not to the gallery chain.
- A successful auto-login is logged at INFO (user, IP), like a password login; failures at WARN without cookie values.
- The admin user must still exist with ROLE_ADMIN (loaded through the admin `UserDetailsService` on each auto-login).

## Anti-goals

Remembered sessions usable by the gallery/family login; tokens readable from the DB (only hashes stored); a cookie that survives "Sign out all devices"; signing out on deploy.

## Left to the builder

Icon drawing method; exact template placement; test fakes.
