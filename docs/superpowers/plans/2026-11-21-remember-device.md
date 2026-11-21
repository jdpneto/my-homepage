# Remember Device + Home-Screen App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phone home-screen shortcut to `/admin/cameras` and a 90-day rolling "Remember this device" admin login that survives deploys.

**Architecture:** Non-rotating DB-backed remember-me tokens (hashed) wired into the admin and camera-API security chains; saved-request-aware login success; a web manifest + icons.

**Tech Stack:** Spring Boot 4.1.2 / Spring Security 7 / Flyway / Thymeleaf; JUnit + MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-21-remember-device-design.md`

## Global Constraints

- Cookie `remember-admin`, value `series:token`, HttpOnly, Secure (configurable off for tests), SameSite=Lax, path `/`, max-age 90 days, re-issued on use; DB stores SHA-256 hex of the token only; 90-day rolling expiry on `last_used_at` (updated at most hourly).
- Login checkbox name `remember-me`, checked by default; label "Remember this device for 90 days".
- After login: saved request, else `/admin/posts`.
- "Sign out all devices" (`POST /api/cameras/auth/revoke-all`) also deletes all remember tokens; response adds `"devices": n`. Logout deletes this device's token and clears the cookie.
- Gallery (`/mae/**`) chain never accepts the cookie.
- Manifest/icons public; manifest `start_url` `/admin/cameras`.
- Commits: no co-credit lines.

## Review Focus

- Two requests at once with the same cookie and no session → both signed in, token still valid (`parallelAutoLoginsDoNotSignOut`).
- A deploy/restart (new context, same DB) → still signed in (`rememberedLoginSurvivesRestart` via a fresh `RememberMeServices` over the same repository).
- "Sign out all devices" → an old cookie no longer signs in (`revokeAllForgetsRememberedDevices`).
- A tampered token for a real series → refused, cookie cleared, other devices unaffected (`wrongTokenIsRefused`).
- The family/gallery password session or a remember cookie → never reaches `/admin/**` as a non-admin; gallery chain ignores the cookie (`galleryIgnoresRememberCookie`).

---

### Task 1: Remember this device + landing page + revoke

**Files:** Create `security/RememberDeviceServices.java`, `security/AdminRememberToken.java` (+ repository), `db/migration/V8__admin_remember_token.sql`; modify `config/SecurityConfig.java`, `cameras/CameraSecurityConfig.java`, `security/RateLimitAuthenticationSuccessHandler.java` usage (`setAlwaysUseDefaultTargetUrl(false)`), `cameras/CameraAuthController.java` (revoke-all), `templates/admin/login.html`, cameras page button label + JS toast; tests `security/RememberDeviceIT.java` (new) with the five Review Focus tests plus `loginWithoutCheckboxSetsNoCookie`, `expiredAfter90DaysIdle`, `lastUsedRolledForward`, `logoutForgetsThisDevice`, `loginReturnsToRequestedPage`; update `AdminAuthIT`/`CameraAuthIT`/`AdminCamerasPageIT` expectations if copy changes.
- [ ] Failing tests → run (RED) → implement → full suite green → commit `feat(admin): remember this device for 90 days (survives deploys), return to the requested page`.

### Task 2: Home-screen app

**Files:** Create `static/cameras.webmanifest`, `static/icons/cameras-192.png`, `cameras-512.png`, `apple-touch-icon.png`; modify `templates/admin/cameras.html`, `templates/admin/login.html`; tests `AdminCamerasPageIT::manifestLinkedAndPublic` (manifest served without login with `start_url` `/admin/cameras`, icons 200 `image/png` with the right pixel sizes, page has `rel="manifest"`, `apple-touch-icon`, `apple-mobile-web-app-capable`).
- [ ] Failing test → implement → green → commit `feat(cameras): add to home screen (web manifest + icons)`.

### Task 3: Ship

- [ ] Fresh review (security-focused) → fix Critical/Important RED→GREEN.
- [ ] Merge, push, deploy (`scripts/deploy.sh`, never `--full`); smoke: manifest public, `/admin/cameras` unauth → login.
- [ ] David: Add to Home Screen, sign in once with the box ticked; after the next deploy he is still signed in.
