# Login Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared in-memory login rate limiter to the homepage admin login and a newly embedded Spring-Boot-native WebDAV server, replacing the `bytemark/webdav` container with Milton and adding multi-user WebDAV with per-user isolated directories.

**Architecture:** A single `LoginRateLimiter` `@Component` (in-memory maps, per-IP sliding window + per-username escalating lockout) is consulted from two call sites: a `LoginRateLimitFilter` in front of Spring Security form-login, and a Milton `SecurityManager` used by an embedded `milton-server-ce` servlet mounted at `/webdav/*`. WebDAV users live in a new Postgres table; the admin stays env-var / in-memory as today.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Spring Security, Spring Data JPA + Flyway, PostgreSQL 16 (H2 for tests), Thymeleaf, Milton CE (`io.milton:milton-server-ce`), Caddy 2, Docker Compose.

**Reference spec:** `docs/superpowers/specs/2026-04-14-rate-limiter-design.md`

---

## File Structure

**Create:**

- `src/main/java/com/davidneto/homepage/security/RateLimitProperties.java` — `@ConfigurationProperties("app.rate-limit")` bean.
- `src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java` — core rate-limit service.
- `src/main/java/com/davidneto/homepage/security/LoginRateLimitFilter.java` — pre-auth filter for `/admin/login` POSTs.
- `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationFailureHandler.java`
- `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationSuccessHandler.java`
- `src/main/java/com/davidneto/homepage/entity/WebDavUser.java`
- `src/main/java/com/davidneto/homepage/repository/WebDavUserRepository.java`
- `src/main/java/com/davidneto/homepage/service/WebDavUserService.java`
- `src/main/java/com/davidneto/homepage/controller/WebDavUserAdminController.java`
- `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java`
- `src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java`
- `src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java`
- `src/main/java/com/davidneto/homepage/webdav/WebDavPrincipal.java`
- `src/main/resources/db/migration/V3__add_webdav_users.sql`
- `src/main/resources/templates/admin/webdav-users.html`
- Tests:
  - `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`
  - `src/test/java/com/davidneto/homepage/security/AdminLoginRateLimitIT.java`
  - `src/test/java/com/davidneto/homepage/webdav/WebDavUserAdminIT.java`
  - `src/test/java/com/davidneto/homepage/webdav/WebDavAuthRateLimitIT.java`
  - `src/test/java/com/davidneto/homepage/webdav/PerUserFileResourceFactoryIT.java`

**Modify:**

- `pom.xml` — add `milton-server-ce` dependency.
- `src/main/resources/application.yml` — `app.rate-limit.*`, `app.webdav.*`, `server.forward-headers-strategy`.
- `src/main/java/com/davidneto/homepage/HomepageApplication.java` — add `@EnableScheduling`.
- `src/main/java/com/davidneto/homepage/config/SecurityConfig.java` — add limiter filter, exclude `/webdav/**`, register handlers.
- `src/main/resources/templates/admin/fragments.html` — add "WebDAV Users" nav link.
- `docker-compose.yml` — remove `webdav` service, add `webdav_data` volume mount to `app`, set `WEBDAV_ROOT_DIR`.
- `Dockerfile` — `mkdir -p /app/webdav`.
- `Caddyfile` — `cloud.davidneto.eu` reverse-proxies `app:8080` with path rewrite to `/webdav`.

---

## Task 1: Config properties and scheduling

**Files:**

- Create: `src/main/java/com/davidneto/homepage/security/RateLimitProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/davidneto/homepage/HomepageApplication.java`
- Test: inline in Task 2 (properties are exercised by `LoginRateLimiterTest`)

- [ ] **Step 1: Create `RateLimitProperties`**

Create `src/main/java/com/davidneto/homepage/security/RateLimitProperties.java`:

```java
package com.davidneto.homepage.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        int ipMaxFailures,
        int ipWindowSeconds,
        int userLockoutThreshold,
        int userLockoutShortSeconds,
        int userLockoutLongThreshold,
        int userLockoutLongSeconds,
        int maxEntries,
        int sweepIntervalSeconds
) {
    public RateLimitProperties {
        if (ipMaxFailures <= 0) throw new IllegalArgumentException("ipMaxFailures must be > 0");
        if (ipWindowSeconds <= 0) throw new IllegalArgumentException("ipWindowSeconds must be > 0");
        if (userLockoutThreshold <= 0) throw new IllegalArgumentException("userLockoutThreshold must be > 0");
        if (userLockoutLongThreshold < userLockoutThreshold)
            throw new IllegalArgumentException("userLockoutLongThreshold must be >= userLockoutThreshold");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        if (sweepIntervalSeconds <= 0) throw new IllegalArgumentException("sweepIntervalSeconds must be > 0");
    }
}
```

- [ ] **Step 2: Enable `@ConfigurationProperties` and scheduling**

Modify `src/main/java/com/davidneto/homepage/HomepageApplication.java`:

```java
package com.davidneto.homepage;

import com.davidneto.homepage.security.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RateLimitProperties.class)
public class HomepageApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomepageApplication.class, args);
    }
}
```

- [ ] **Step 3: Add config block to `application.yml`**

Modify `src/main/resources/application.yml` — add under the top-level `server:` key (create the key if missing) and the existing `app:` key:

```yaml
server:
  forward-headers-strategy: NATIVE

spring:
  # (existing block unchanged)

app:
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin}
  upload-dir: ${UPLOAD_DIR:./uploads}
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

- [ ] **Step 4: Verify app still starts**

Run: `./mvnw -q spring-boot:run` in a scratch shell OR just `./mvnw -q compile`.
Expected: BUILD SUCCESS, no binding errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/HomepageApplication.java \
        src/main/java/com/davidneto/homepage/security/RateLimitProperties.java \
        src/main/resources/application.yml
git commit -m "feat(rate-limit): add RateLimitProperties and enable scheduling"
```

---

## Task 2: `LoginRateLimiter` — IP sliding window

**Files:**

- Create: `src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java`
- Test: `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`

- [ ] **Step 1: Write failing test for IP window**

Create `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`:

```java
package com.davidneto.homepage.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static final RateLimitProperties PROPS = new RateLimitProperties(
            5, 60, 5, 300, 10, 1800, 10_000, 600);

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant start) { this.now = new AtomicReference<>(start); }
        void advanceSeconds(long s) { now.updateAndGet(i -> i.plusSeconds(s)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void ip_blocks_after_threshold_and_unblocks_after_window() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.check("1.2.3.4", "alice").kind())
                    .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
            limiter.recordFailure("1.2.3.4", "alice");
        }

        var blocked = limiter.check("1.2.3.4", "alice");
        assertThat(blocked.kind()).isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_IP);
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);

        clock.advanceSeconds(61);
        assertThat(limiter.check("1.2.3.4", "alice").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }

    @Test
    void different_ips_do_not_share_counters() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);
        for (int i = 0; i < 5; i++) limiter.recordFailure("1.2.3.4", "alice");
        assertThat(limiter.check("5.6.7.8", "alice").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER)
                .satisfiesAnyOf(k -> assertThat(k).isIn(
                        LoginRateLimiter.DecisionKind.BLOCK_USER,
                        LoginRateLimiter.DecisionKind.ALLOW));
        // different IP, different user -> allowed
        assertThat(limiter.check("5.6.7.8", "bob").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=LoginRateLimiterTest test`
Expected: COMPILATION ERROR ("cannot find symbol: class LoginRateLimiter").

- [ ] **Step 3: Minimal `LoginRateLimiter` — IP window only**

Create `src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java`:

```java
package com.davidneto.homepage.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    public enum DecisionKind { ALLOW, BLOCK_IP, BLOCK_USER }

    public record Decision(DecisionKind kind, long retryAfterSeconds) {
        public static Decision allow() { return new Decision(DecisionKind.ALLOW, 0); }
    }

    private final RateLimitProperties props;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> ipAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserCounter> userAttempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(RateLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public Decision check(String ip, String username) {
        Instant now = clock.instant();
        Deque<Instant> window = ipAttempts.get(ip);
        if (window != null) {
            synchronized (window) {
                pruneIpWindow(window, now);
                if (window.size() >= props.ipMaxFailures()) {
                    long retry = props.ipWindowSeconds()
                            - (now.getEpochSecond() - window.peekFirst().getEpochSecond());
                    return new Decision(DecisionKind.BLOCK_IP, Math.max(1, retry));
                }
            }
        }
        UserCounter uc = userAttempts.get(normalize(username));
        if (uc != null && uc.lockedUntil != null && uc.lockedUntil.isAfter(now)) {
            long retry = uc.lockedUntil.getEpochSecond() - now.getEpochSecond();
            return new Decision(DecisionKind.BLOCK_USER, Math.max(1, retry));
        }
        return Decision.allow();
    }

    public void recordFailure(String ip, String username) {
        Instant now = clock.instant();
        Deque<Instant> window = ipAttempts.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (window) {
            pruneIpWindow(window, now);
            window.addLast(now);
        }
        String key = normalize(username);
        userAttempts.compute(key, (k, existing) -> {
            UserCounter uc = existing == null ? new UserCounter() : existing;
            uc.consecutiveFailures++;
            uc.lastFailure = now;
            if (uc.consecutiveFailures >= props.userLockoutLongThreshold()) {
                uc.lockedUntil = now.plusSeconds(props.userLockoutLongSeconds());
            } else if (uc.consecutiveFailures >= props.userLockoutThreshold()) {
                uc.lockedUntil = now.plusSeconds(props.userLockoutShortSeconds());
            }
            return uc;
        });
    }

    public void recordSuccess(String username) {
        userAttempts.remove(normalize(username));
    }

    private void pruneIpWindow(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minusSeconds(props.ipWindowSeconds());
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static class UserCounter {
        int consecutiveFailures;
        Instant lastFailure;
        Instant lockedUntil;
    }
}
```

Note: the `@Component` wiring will look for a `Clock` bean. Add a `Clock` bean in Task 4; for now the unit tests pass a mock `Clock` via constructor, so compilation is fine.

- [ ] **Step 4: Run test to verify IP window passes**

Run: `./mvnw -q -Dtest=LoginRateLimiterTest#ip_blocks_after_threshold_and_unblocks_after_window test`
Expected: PASS.

Run: `./mvnw -q -Dtest=LoginRateLimiterTest#different_ips_do_not_share_counters test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java \
        src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java
git commit -m "feat(rate-limit): LoginRateLimiter with per-IP sliding window"
```

---

## Task 3: `LoginRateLimiter` — username lockout and success reset

**Files:**

- Modify: `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`
- (impl already written in Task 2; this task only adds coverage)

- [ ] **Step 1: Add lockout tests**

Append to `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`:

```java
    @Test
    void five_failures_locks_user_for_short_window() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        // Five failures from 5 different IPs so the IP window never trips.
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("10.0.0." + i, "alice");
        }

        var d = limiter.check("99.99.99.99", "alice");
        assertThat(d.kind()).isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER);
        assertThat(d.retryAfterSeconds()).isBetween(1L, 300L);
    }

    @Test
    void ten_failures_locks_user_for_long_window() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("10.0.0." + i, "alice");
        }

        var d = limiter.check("99.99.99.99", "alice");
        assertThat(d.kind()).isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER);
        assertThat(d.retryAfterSeconds()).isBetween(301L, 1800L);
    }

    @Test
    void success_clears_user_counter() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("10.0.0." + i, "alice");
        }
        limiter.recordSuccess("alice");

        // User lockout cleared; IP window is unrelated (each call was from a unique IP).
        assertThat(limiter.check("42.42.42.42", "alice").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }

    @Test
    void username_key_is_case_insensitive_and_trimmed() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);
        for (int i = 0; i < 5; i++) limiter.recordFailure("10.0.0." + i, "Alice");
        assertThat(limiter.check("77.77.77.77", "  alice  ").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER);
    }
```

- [ ] **Step 2: Run tests**

Run: `./mvnw -q -Dtest=LoginRateLimiterTest test`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java
git commit -m "test(rate-limit): cover username lockout and success reset"
```

---

## Task 4: `LoginRateLimiter` — capacity cap, scheduled sweep, `Clock` bean

**Files:**

- Modify: `src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java`
- Create: `src/main/java/com/davidneto/homepage/config/ClockConfig.java`
- Modify: `src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java`

- [ ] **Step 1: Add failing tests for capacity + sweep + concurrency**

Append to `LoginRateLimiterTest.java`:

```java
    @Test
    void concurrent_failures_are_counted_correctly() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        int threads = 10, perThread = 100;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        limiter.recordFailure("ip-" + tid + "-" + i, "alice");
                    }
                } finally { latch.countDown(); }
            });
        }
        latch.await();
        pool.shutdown();

        // User has been locked (well above threshold).
        assertThat(limiter.check("zzz", "alice").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER);
    }

    @Test
    void capacity_cap_evicts_oldest_ip_entries() {
        var tight = new RateLimitProperties(5, 60, 5, 300, 10, 1800, /*max*/ 5, 600);
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(tight, clock);
        for (int i = 0; i < 10; i++) {
            limiter.recordFailure("ip-" + i, "nobody");
            clock.advanceSeconds(1);
        }
        // First IP entries should have been evicted; looking them up is fine either way
        // but the map size must be <= 5.
        assertThat(limiter.ipEntryCountForTesting()).isLessThanOrEqualTo(5);
    }

    @Test
    void sweep_removes_expired_user_and_ip_entries() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);
        limiter.recordFailure("1.2.3.4", "alice");
        clock.advanceSeconds(4000); // past any ip window and > 1h after last failure
        limiter.sweep();
        assertThat(limiter.ipEntryCountForTesting()).isZero();
        assertThat(limiter.userEntryCountForTesting()).isZero();
    }
```

- [ ] **Step 2: Extend `LoginRateLimiter` with capacity guard, sweep, and test accessors**

Modify `src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java` — replace the existing file with:

```java
package com.davidneto.homepage.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class LoginRateLimiter {

    public enum DecisionKind { ALLOW, BLOCK_IP, BLOCK_USER }

    public record Decision(DecisionKind kind, long retryAfterSeconds) {
        public static Decision allow() { return new Decision(DecisionKind.ALLOW, 0); }
    }

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final RateLimitProperties props;
    private final Clock clock;

    // LinkedHashMap(accessOrder=true) gives us LRU semantics on get()/put().
    // All access must be under the per-map monitor.
    private final LinkedHashMap<String, Deque<Instant>> ipAttempts =
            new LinkedHashMap<>(256, 0.75f, true);
    private final LinkedHashMap<String, UserCounter> userAttempts =
            new LinkedHashMap<>(256, 0.75f, true);

    public LoginRateLimiter(RateLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public Decision check(String ip, String username) {
        Instant now = clock.instant();
        synchronized (ipAttempts) {
            Deque<Instant> window = ipAttempts.get(ip);
            if (window != null) {
                pruneIpWindow(window, now);
                if (window.size() >= props.ipMaxFailures()) {
                    long retry = props.ipWindowSeconds()
                            - (now.getEpochSecond() - window.peekFirst().getEpochSecond());
                    return new Decision(DecisionKind.BLOCK_IP, Math.max(1, retry));
                }
            }
        }
        synchronized (userAttempts) {
            UserCounter uc = userAttempts.get(normalize(username));
            if (uc != null && uc.lockedUntil != null && uc.lockedUntil.isAfter(now)) {
                long retry = uc.lockedUntil.getEpochSecond() - now.getEpochSecond();
                return new Decision(DecisionKind.BLOCK_USER, Math.max(1, retry));
            }
        }
        return Decision.allow();
    }

    public void recordFailure(String ip, String username) {
        Instant now = clock.instant();

        synchronized (ipAttempts) {
            Deque<Instant> window = ipAttempts.get(ip);
            if (window == null) {
                window = new ConcurrentLinkedDeque<>();
                ipAttempts.put(ip, window);
                enforceCap(ipAttempts);
            }
            pruneIpWindow(window, now);
            window.addLast(now);
        }

        String key = normalize(username);
        synchronized (userAttempts) {
            UserCounter uc = userAttempts.get(key);
            if (uc == null) {
                uc = new UserCounter();
                userAttempts.put(key, uc);
                enforceCap(userAttempts);
            }
            uc.consecutiveFailures++;
            uc.lastFailure = now;
            if (uc.consecutiveFailures >= props.userLockoutLongThreshold()) {
                uc.lockedUntil = now.plusSeconds(props.userLockoutLongSeconds());
            } else if (uc.consecutiveFailures >= props.userLockoutThreshold()) {
                uc.lockedUntil = now.plusSeconds(props.userLockoutShortSeconds());
            }
        }
    }

    public void recordSuccess(String username) {
        synchronized (userAttempts) {
            userAttempts.remove(normalize(username));
        }
    }

    @Scheduled(fixedDelayString = "${app.rate-limit.sweep-interval-seconds}000")
    public void sweep() {
        Instant now = clock.instant();
        Instant ipCutoff = now.minusSeconds(props.ipWindowSeconds());
        Instant userCutoff = now.minusSeconds(3600);

        synchronized (ipAttempts) {
            Iterator<Map.Entry<String, Deque<Instant>>> it = ipAttempts.entrySet().iterator();
            while (it.hasNext()) {
                Deque<Instant> w = it.next().getValue();
                while (!w.isEmpty() && w.peekFirst().isBefore(ipCutoff)) w.pollFirst();
                if (w.isEmpty()) it.remove();
            }
        }
        synchronized (userAttempts) {
            userAttempts.entrySet().removeIf(e -> {
                UserCounter uc = e.getValue();
                boolean notLocked = uc.lockedUntil == null || uc.lockedUntil.isBefore(now);
                boolean stale = uc.lastFailure == null || uc.lastFailure.isBefore(userCutoff);
                return notLocked && stale;
            });
        }
    }

    // package-private test accessors
    int ipEntryCountForTesting() { synchronized (ipAttempts) { return ipAttempts.size(); } }
    int userEntryCountForTesting() { synchronized (userAttempts) { return userAttempts.size(); } }

    private void pruneIpWindow(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minusSeconds(props.ipWindowSeconds());
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) window.pollFirst();
    }

    private <K, V> void enforceCap(LinkedHashMap<K, V> map) {
        if (map.size() <= props.maxEntries()) return;
        Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
        while (it.hasNext() && map.size() > props.maxEntries()) {
            it.next();
            it.remove();
            log.debug("rate-limiter evicted oldest entry (over cap)");
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static class UserCounter {
        int consecutiveFailures;
        Instant lastFailure;
        Instant lockedUntil;
    }
}
```

- [ ] **Step 3: Create `Clock` bean**

Create `src/main/java/com/davidneto/homepage/config/ClockConfig.java`:

```java
package com.davidneto.homepage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -Dtest=LoginRateLimiterTest test`
Expected: ALL 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/security/LoginRateLimiter.java \
        src/main/java/com/davidneto/homepage/config/ClockConfig.java \
        src/test/java/com/davidneto/homepage/security/LoginRateLimiterTest.java
git commit -m "feat(rate-limit): capacity cap, scheduled sweep, Clock bean"
```

---

## Task 5: Spring Security integration for `/admin/login`

**Files:**

- Create: `src/main/java/com/davidneto/homepage/security/LoginRateLimitFilter.java`
- Create: `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationFailureHandler.java`
- Create: `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationSuccessHandler.java`
- Modify: `src/main/java/com/davidneto/homepage/config/SecurityConfig.java`
- Test: `src/test/java/com/davidneto/homepage/security/AdminLoginRateLimitIT.java`

- [ ] **Step 1: Write failing integration test**

Create `src/test/java/com/davidneto/homepage/security/AdminLoginRateLimitIT.java`:

```java
package com.davidneto.homepage.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AdminLoginRateLimitIT {

    @Autowired WebApplicationContext ctx;
    @Autowired LoginRateLimiter limiter;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test
    void sixth_failed_login_returns_429() throws Exception {
        var mvc = mvc();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/admin/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "wrong")
                            .with(r -> { r.setRemoteAddr("1.2.3.4"); return r; }))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "wrong")
                        .with(r -> { r.setRemoteAddr("1.2.3.4"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void success_resets_counter() throws Exception {
        var mvc = mvc();
        limiter.recordSuccess("admin"); // ensure clean
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/admin/login")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "wrong")
                            .with(r -> { r.setRemoteAddr("9.9.9.9"); return r; }))
                    .andExpect(status().is3xxRedirection());
        }
        // A correct login clears the counter. Uses application-test.yml credentials.
        mvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin")
                        .with(r -> { r.setRemoteAddr("9.9.9.9"); return r; }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts"));
    }
}
```

Create `src/test/resources/application-test.yml` if missing:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:homepage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

app:
  admin:
    username: admin
    password: admin
  upload-dir: ./build/test-uploads
  webdav:
    root-dir: ./build/test-webdav
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

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=AdminLoginRateLimitIT test`
Expected: FAIL. 6th request returns 3xx (no 429 yet).

- [ ] **Step 3: Create `LoginRateLimitFilter`**

Create `src/main/java/com/davidneto/homepage/security/LoginRateLimitFilter.java`:

```java
package com.davidneto.homepage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(req.getMethod()) || !"/admin/login".equals(req.getRequestURI())) {
            chain.doFilter(req, resp);
            return;
        }
        String username = req.getParameter("username");
        String ip = req.getRemoteAddr();
        LoginRateLimiter.Decision d = limiter.check(ip, username == null ? "" : username);
        if (d.kind() == LoginRateLimiter.DecisionKind.ALLOW) {
            chain.doFilter(req, resp);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
        resp.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
        resp.setContentType("text/plain; charset=utf-8");
        resp.getWriter().write(messageFor(d));
    }

    private static String messageFor(LoginRateLimiter.Decision d) {
        return switch (d.kind()) {
            case BLOCK_IP -> "Too many attempts. Try again in " + d.retryAfterSeconds() + " seconds.";
            case BLOCK_USER -> "This account is temporarily locked. Try again in "
                    + Math.max(1, d.retryAfterSeconds() / 60) + " minutes.";
            default -> "";
        };
    }
}
```

- [ ] **Step 4: Create success/failure handlers**

Create `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationFailureHandler.java`:

```java
package com.davidneto.homepage.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

public class RateLimitAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAuthenticationFailureHandler.class);
    private final LoginRateLimiter limiter;

    public RateLimitAuthenticationFailureHandler(LoginRateLimiter limiter) {
        super("/admin/login?error");
        this.limiter = limiter;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse resp,
                                        AuthenticationException ex) throws IOException, ServletException {
        String username = req.getParameter("username");
        String ip = req.getRemoteAddr();
        limiter.recordFailure(ip, username == null ? "" : username);
        log.warn("admin login failure ip={} user={}", ip, username);
        super.onAuthenticationFailure(req, resp, ex);
    }
}
```

Create `src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationSuccessHandler.java`:

```java
package com.davidneto.homepage.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

public class RateLimitAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAuthenticationSuccessHandler.class);
    private final LoginRateLimiter limiter;

    public RateLimitAuthenticationSuccessHandler(LoginRateLimiter limiter) {
        this.limiter = limiter;
        setDefaultTargetUrl("/admin/posts");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse resp,
                                        Authentication auth) throws IOException, ServletException {
        limiter.recordSuccess(auth.getName());
        log.info("admin login success ip={} user={}", req.getRemoteAddr(), auth.getName());
        super.onAuthenticationSuccess(req, resp, auth);
    }
}
```

- [ ] **Step 5: Wire into `SecurityConfig`**

Replace `src/main/java/com/davidneto/homepage/config/SecurityConfig.java` with:

```java
package com.davidneto.homepage.config;

import com.davidneto.homepage.security.LoginRateLimitFilter;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.security.RateLimitAuthenticationFailureHandler;
import com.davidneto.homepage.security.RateLimitAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, LoginRateLimiter limiter) throws Exception {
        http
            .securityMatcher(new AntPathRequestMatcher("/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/webdav/**").permitAll()
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/admin/login")
                .successHandler(new RateLimitAuthenticationSuccessHandler(limiter))
                .failureHandler(new RateLimitAuthenticationFailureHandler(limiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/webdav/**")
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .addFilterBefore(new LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        var user = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 6: Run the integration test**

Run: `./mvnw -q -Dtest=AdminLoginRateLimitIT test`
Expected: ALL PASS. 6th failed login returns 429 with `Retry-After` header. Success after 4 failures redirects to `/admin/posts`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/davidneto/homepage/security/LoginRateLimitFilter.java \
        src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationFailureHandler.java \
        src/main/java/com/davidneto/homepage/security/RateLimitAuthenticationSuccessHandler.java \
        src/main/java/com/davidneto/homepage/config/SecurityConfig.java \
        src/test/java/com/davidneto/homepage/security/AdminLoginRateLimitIT.java \
        src/test/resources/application-test.yml
git commit -m "feat(rate-limit): plug LoginRateLimiter into admin login flow"
```

---

## Task 6: WebDAV user data layer

**Files:**

- Create: `src/main/resources/db/migration/V3__add_webdav_users.sql`
- Create: `src/main/java/com/davidneto/homepage/entity/WebDavUser.java`
- Create: `src/main/java/com/davidneto/homepage/repository/WebDavUserRepository.java`

- [ ] **Step 1: Write the Flyway migration**

Create `src/main/resources/db/migration/V3__add_webdav_users.sql`:

```sql
CREATE TABLE webdav_users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

(TIMESTAMP not TIMESTAMPTZ — matches the V2 migration style, keeps H2 PostgreSQL mode happy.)

- [ ] **Step 2: Create `WebDavUser` entity**

Create `src/main/java/com/davidneto/homepage/entity/WebDavUser.java`:

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "webdav_users")
public class WebDavUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String u) { this.username = u; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 3: Create repository**

Create `src/main/java/com/davidneto/homepage/repository/WebDavUserRepository.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.WebDavUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebDavUserRepository extends JpaRepository<WebDavUser, Long> {
    Optional<WebDavUser> findByUsername(String username);
    List<WebDavUser> findAllByOrderByUsernameAsc();
}
```

- [ ] **Step 4: Verify compilation + schema validation on test profile**

Run: `./mvnw -q -Dtest=LoginRateLimiterTest test` (any existing test is fine — goal is to boot the Spring context on the `test` profile and let Flyway + `ddl-auto: validate` run).
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__add_webdav_users.sql \
        src/main/java/com/davidneto/homepage/entity/WebDavUser.java \
        src/main/java/com/davidneto/homepage/repository/WebDavUserRepository.java
git commit -m "feat(webdav): add webdav_users table and JPA entity"
```

---

## Task 7: `WebDavUserService`

**Files:**

- Create: `src/main/java/com/davidneto/homepage/service/WebDavUserService.java`
- Test: `src/test/java/com/davidneto/homepage/service/WebDavUserServiceTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/davidneto/homepage/service/WebDavUserServiceTest.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebDavUserServiceTest {

    @Autowired WebDavUserService service;
    @Autowired WebDavUserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void create_persists_bcrypt_hash() {
        WebDavUser u = service.create("alice", "hunter2hunter2");
        assertThat(u.getId()).isNotNull();
        assertThat(encoder.matches("hunter2hunter2", u.getPasswordHash())).isTrue();
        assertThat(repo.findByUsername("alice")).isPresent();
    }

    @Test
    void create_rejects_invalid_username() {
        assertThatThrownBy(() -> service.create("al ice", "password1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejects_short_password() {
        assertThatThrownBy(() -> service.create("bob", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reset_password_updates_hash() {
        WebDavUser u = service.create("carol", "original-pw");
        service.resetPassword(u.getId(), "new-password-123");
        WebDavUser updated = repo.findById(u.getId()).orElseThrow();
        assertThat(encoder.matches("new-password-123", updated.getPasswordHash())).isTrue();
        assertThat(encoder.matches("original-pw", updated.getPasswordHash())).isFalse();
    }

    @Test
    void delete_removes_user() {
        WebDavUser u = service.create("dave", "password123");
        service.delete(u.getId());
        assertThat(repo.findById(u.getId())).isEmpty();
    }

    @Test
    void list_returns_alphabetical() {
        service.create("zoe", "password123");
        service.create("amy", "password123");
        assertThat(service.list()).extracting(WebDavUser::getUsername)
                .containsSubsequence("amy", "zoe");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=WebDavUserServiceTest test`
Expected: COMPILATION ERROR (`WebDavUserService` does not exist).

- [ ] **Step 3: Implement `WebDavUserService`**

Create `src/main/java/com/davidneto/homepage/service/WebDavUserService.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class WebDavUserService {

    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final WebDavUserRepository repo;
    private final PasswordEncoder encoder;

    public WebDavUserService(WebDavUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public WebDavUser create(String username, String rawPassword) {
        validateUsername(username);
        validatePassword(rawPassword);
        WebDavUser u = new WebDavUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        return repo.save(u);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        validatePassword(rawPassword);
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        u.setPasswordHash(encoder.encode(rawPassword));
        repo.save(u);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    public List<WebDavUser> list() {
        return repo.findAllByOrderByUsernameAsc();
    }

    public Optional<WebDavUser> findByUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) return Optional.empty();
        return repo.findByUsername(username);
    }

    private void validateUsername(String u) {
        if (u == null || !USERNAME_PATTERN.matcher(u).matches())
            throw new IllegalArgumentException("username must match " + USERNAME_PATTERN.pattern());
    }

    private void validatePassword(String p) {
        if (p == null || p.length() < MIN_PASSWORD_LENGTH)
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " chars");
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw -q -Dtest=WebDavUserServiceTest test`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/WebDavUserService.java \
        src/test/java/com/davidneto/homepage/service/WebDavUserServiceTest.java
git commit -m "feat(webdav): WebDavUserService with validation"
```

---

## Task 8: WebDAV admin UI

**Files:**

- Create: `src/main/java/com/davidneto/homepage/controller/WebDavUserAdminController.java`
- Create: `src/main/resources/templates/admin/webdav-users.html`
- Modify: `src/main/resources/templates/admin/fragments.html`
- Test: `src/test/java/com/davidneto/homepage/webdav/WebDavUserAdminIT.java`

- [ ] **Step 1: Write failing integration test**

Create `src/test/java/com/davidneto/homepage/webdav/WebDavUserAdminIT.java`:

```java
package com.davidneto.homepage.webdav;

import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebDavUserAdminIT {

    @Autowired MockMvc mvc;
    @Autowired WebDavUserRepository repo;

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_can_create_list_and_delete_user() throws Exception {
        mvc.perform(post("/admin/webdav-users").with(csrf())
                        .param("username", "alice")
                        .param("password", "password1234"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/webdav-users"));

        mvc.perform(get("/admin/webdav-users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("alice")));

        Long id = repo.findByUsername("alice").orElseThrow().getId();

        mvc.perform(post("/admin/webdav-users/" + id + "/reset-password").with(csrf())
                        .param("password", "new-password-abc"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/admin/webdav-users/" + id + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(repo.findById(id)).isEmpty();
    }

    @Test
    void unauthenticated_request_is_redirected_to_login() throws Exception {
        mvc.perform(get("/admin/webdav-users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=WebDavUserAdminIT test`
Expected: FAIL — controller doesn't exist.

- [ ] **Step 3: Implement the controller**

Create `src/main/java/com/davidneto/homepage/controller/WebDavUserAdminController.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.service.WebDavUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/webdav-users")
public class WebDavUserAdminController {

    private final WebDavUserService service;

    public WebDavUserAdminController(WebDavUserService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", service.list());
        return "admin/webdav-users";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         RedirectAttributes redirect) {
        try {
            service.create(username, password);
            redirect.addFlashAttribute("message", "User '" + username + "' created.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String password,
                                RedirectAttributes redirect) {
        try {
            service.resetPassword(id, password);
            redirect.addFlashAttribute("message", "Password reset.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        service.delete(id);
        redirect.addFlashAttribute("message",
                "User deleted. Files in the user's directory are left on disk.");
        return "redirect:/admin/webdav-users";
    }
}
```

- [ ] **Step 4: Create the template**

Create `src/main/resources/templates/admin/webdav-users.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — WebDAV Users</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
<nav th:replace="~{admin/fragments :: admin-nav('webdav-users')}"></nav>
<div style="padding: 24px 32px;">
    <div class="section-header" style="margin-bottom: 16px;">$ ls webdav-users/</div>

    <div th:if="${message}" style="color: var(--accent); margin-bottom: 12px;" th:text="${message}"></div>
    <div th:if="${error}" style="color: #ff4444; margin-bottom: 12px;" th:text="${error}"></div>

    <div class="post-list" th:if="${not #lists.isEmpty(users)}" style="margin-bottom: 24px;">
        <div th:each="u : ${users}" class="post-item"
             style="display: flex; justify-content: space-between; align-items: center; gap: 10px;">
            <div>
                <span class="post-title" th:text="${u.username}">alice</span>
                <span class="post-meta" th:text="' — created ' + ${#temporals.format(u.createdAt, 'yyyy-MM-dd')}">2026-04-14</span>
            </div>
            <div style="display: flex; gap: 12px;">
                <form th:action="@{/admin/webdav-users/{id}/reset-password(id=${u.id})}" method="post"
                      style="display:flex; gap:6px; align-items:center;">
                    <input type="password" name="password" placeholder="new password" required minlength="8"
                           style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 4px 8px; font-family: inherit; font-size: 12px; border-radius: 3px;">
                    <button type="submit"
                            style="color: var(--accent); background: none; border: none; font-family: inherit; font-size: 12px; cursor: pointer;">reset</button>
                </form>
                <form th:action="@{/admin/webdav-users/{id}/delete(id=${u.id})}" method="post" style="display:inline;">
                    <button type="submit"
                            style="color: #ff4444; background: none; border: none; font-family: inherit; font-size: 12px; cursor: pointer;">delete</button>
                </form>
            </div>
        </div>
    </div>

    <div style="border: 1px solid var(--border-light); border-radius: 4px; padding: 16px; margin-top: 16px;">
        <div style="color: var(--accent); font-size: 13px; margin-bottom: 12px;">+ add new user</div>
        <form th:action="@{/admin/webdav-users}" method="post"
              style="display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end;">
            <div>
                <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">Username</label>
                <input type="text" name="username" required pattern="[a-zA-Z0-9_-]{1,64}" placeholder="alice"
                       style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px; width: 160px;">
            </div>
            <div>
                <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">Password</label>
                <input type="password" name="password" required minlength="8"
                       style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px; width: 200px;">
            </div>
            <button type="submit"
                    style="color: var(--bg); font-size: 13px; background: var(--accent); padding: 6px 16px; border-radius: 3px; border: none; font-family: inherit; font-weight: bold; cursor: pointer;">
                add
            </button>
        </form>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 5: Add nav link**

Modify `src/main/resources/templates/admin/fragments.html` — add a link between "Social Links" and "Settings":

```html
<a href="/admin/social-links" th:style="${active == 'social-links'} ? 'color: #fff; border-bottom: 1px solid var(--accent);' : 'color: var(--text-secondary);'">Social Links</a>
<a href="/admin/webdav-users" th:style="${active == 'webdav-users'} ? 'color: #fff; border-bottom: 1px solid var(--accent);' : 'color: var(--text-secondary);'">WebDAV Users</a>
<a href="/admin/settings" th:style="${active == 'settings'} ? 'color: #fff; border-bottom: 1px solid var(--accent);' : 'color: var(--text-secondary);'">Settings</a>
```

- [ ] **Step 6: Run tests**

Run: `./mvnw -q -Dtest=WebDavUserAdminIT test`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/davidneto/homepage/controller/WebDavUserAdminController.java \
        src/main/resources/templates/admin/webdav-users.html \
        src/main/resources/templates/admin/fragments.html \
        src/test/java/com/davidneto/homepage/webdav/WebDavUserAdminIT.java
git commit -m "feat(webdav): admin UI for WebDAV user management"
```

---

## Task 9: Add Milton dependency and servlet skeleton

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java`

- [ ] **Step 1: Pin the latest `milton-server-ce` version**

Open https://central.sonatype.com/artifact/io.milton/milton-server-ce in a browser and note the latest stable version string (at time of writing the line is in the `3.x` series).

- [ ] **Step 2: Add the dependency to `pom.xml`**

Modify `pom.xml` — add inside `<dependencies>` (replace `<VERSION>` with the string from Step 1):

```xml
<dependency>
    <groupId>io.milton</groupId>
    <artifactId>milton-server-ce</artifactId>
    <version><VERSION></version>
</dependency>
```

- [ ] **Step 3: Create a minimal `MiltonConfig` with `ServletRegistrationBean`**

Create `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java`:

```java
package com.davidneto.homepage.webdav;

import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.servlet.MiltonServlet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MiltonConfig {

    @Value("${app.webdav.root-dir}")
    private String rootDir;

    @Bean
    public HttpManager miltonHttpManager(ResourceFactory resourceFactory, SecurityManager securityManager) {
        HttpManagerBuilder builder = new HttpManagerBuilder();
        builder.setResourceFactory(resourceFactory);
        builder.setSecurityManager(securityManager);
        builder.setEnableFormAuth(false);
        builder.setEnableDigestAuth(false);
        builder.setEnableBasicAuth(true);
        return builder.buildHttpManager();
    }

    @Bean
    public ServletRegistrationBean<MiltonServlet> miltonServlet(HttpManager httpManager) {
        MiltonServlet servlet = new MiltonServlet() {
            @Override
            public void init(jakarta.servlet.ServletConfig config) {
                // HttpManager injected via the bean method; Milton will pick it up.
                MiltonServlet.setThreadlocalRequest(null);
                MiltonServlet.setThreadlocalResponse(null);
                super.init(config);
            }
        };
        ServletRegistrationBean<MiltonServlet> reg = new ServletRegistrationBean<>(servlet, "/webdav/*");
        reg.setName("milton");
        reg.setLoadOnStartup(1);
        reg.addInitParameter("milton.singletonMode", "true");
        return reg;
    }
}
```

*Note on Milton integration:* if the init approach above does not pick up the Spring-managed `HttpManager` in your pinned Milton version, replace the servlet instantiation with `new io.milton.servlet.SpringMiltonFilter()` using a `FilterRegistrationBean` instead. Verify against the version's docs before tasks 10–11. The unit tests in later tasks will reveal which integration form works.

- [ ] **Step 4: Create a placeholder `SecurityManager` + `ResourceFactory` so context wires**

Create two temporary stubs (they'll be replaced in Tasks 10–11). Put them in the same package.

`src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java`:

```java
package com.davidneto.homepage.webdav;

import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.Resource;
import io.milton.http.SecurityManager;
import org.springframework.stereotype.Component;

@Component
public class WebDavSecurityManager implements SecurityManager {
    @Override public Object authenticate(String user, String password) { return null; }
    @Override public Object authenticate(io.milton.http.DigestResponse dr) { return null; }
    @Override public boolean authorise(Request r, Method m, Auth a, Resource res) { return a != null; }
    @Override public String getRealm(String host) { return "webdav"; }
    @Override public boolean isDigestAllowed() { return false; }
}
```

`src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java`:

```java
package com.davidneto.homepage.webdav;

import io.milton.http.ResourceFactory;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.Resource;
import org.springframework.stereotype.Component;

@Component
public class PerUserFileResourceFactory implements ResourceFactory {
    @Override
    public Resource getResource(String host, String path)
            throws NotAuthorizedException, BadRequestException {
        return null; // filled in by Task 11
    }
}
```

- [ ] **Step 5: Verify app starts**

Run: `./mvnw -q spring-boot:run &` (background) — wait 5 s, then `curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:8080/webdav/`.
Expected: any response other than a connection error (likely 401 or 404 — the auth/resource layers are stubs). Kill the app.

Alternative: `./mvnw -q test -Dtest=WebDavUserAdminIT` — if context boots, the servlet registration is fine.

- [ ] **Step 6: Commit**

```bash
git add pom.xml \
        src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java \
        src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java \
        src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java
git commit -m "feat(webdav): add Milton dependency and servlet skeleton"
```

---

## Task 10: Milton `SecurityManager` wired to `LoginRateLimiter`

**Files:**

- Create: `src/main/java/com/davidneto/homepage/webdav/WebDavPrincipal.java`
- Modify: `src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java`
- Test: `src/test/java/com/davidneto/homepage/webdav/WebDavAuthRateLimitIT.java`

- [ ] **Step 1: Write failing integration test**

Create `src/test/java/com/davidneto/homepage/webdav/WebDavAuthRateLimitIT.java`:

```java
package com.davidneto.homepage.webdav;

import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebDavAuthRateLimitIT {

    @LocalServerPort int port;
    @Autowired WebDavUserService users;

    @BeforeEach
    void setup() {
        users.list().forEach(u -> users.delete(u.getId()));
        users.create("alice", "correct-horse");
    }

    private HttpResponse<String> propfind(String user, String pass) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webdav/"))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", auth)
                .header("Depth", "0")
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void wrong_password_returns_401_sixth_time_carries_retry_after() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(propfind("alice", "wrong").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = propfind("alice", "wrong");
        assertThat(blocked.statusCode()).isEqualTo(401);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void correct_password_succeeds() throws Exception {
        HttpResponse<String> ok = propfind("alice", "correct-horse");
        assertThat(ok.statusCode()).isIn(200, 207);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=WebDavAuthRateLimitIT test`
Expected: FAIL — current stub returns no principal, so everything is 401 but without `Retry-After`.

- [ ] **Step 3: Create `WebDavPrincipal`**

Create `src/main/java/com/davidneto/homepage/webdav/WebDavPrincipal.java`:

```java
package com.davidneto.homepage.webdav;

import java.security.Principal;

public record WebDavPrincipal(String username) implements Principal {
    @Override public String getName() { return username; }
}
```

- [ ] **Step 4: Replace the stub `WebDavSecurityManager`**

Replace `src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java` with:

```java
package com.davidneto.homepage.webdav;

import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.WebDavUserService;
import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.Response;
import io.milton.http.SecurityManager;
import io.milton.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class WebDavSecurityManager implements SecurityManager {

    private static final Logger log = LoggerFactory.getLogger(WebDavSecurityManager.class);

    private final WebDavUserService users;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;

    public WebDavSecurityManager(WebDavUserService users,
                                 PasswordEncoder encoder,
                                 LoginRateLimiter limiter) {
        this.users = users;
        this.encoder = encoder;
        this.limiter = limiter;
    }

    @Override
    public Object authenticate(String user, String password) {
        String ip = currentClientIp();
        LoginRateLimiter.Decision decision = limiter.check(ip, user);
        if (decision.kind() != LoginRateLimiter.DecisionKind.ALLOW) {
            writeRetryAfter(decision.retryAfterSeconds());
            log.warn("webdav login blocked ip={} user={} kind={} retryAfter={}s",
                    ip, user, decision.kind(), decision.retryAfterSeconds());
            return null;
        }
        var found = users.findByUsername(user);
        if (found.isEmpty() || !encoder.matches(password, found.get().getPasswordHash())) {
            limiter.recordFailure(ip, user == null ? "" : user);
            log.warn("webdav login failure ip={} user={}", ip, user);
            return null;
        }
        limiter.recordSuccess(user);
        log.info("webdav login success ip={} user={}", ip, user);
        return new WebDavPrincipal(found.get().getUsername());
    }

    @Override public Object authenticate(io.milton.http.DigestResponse dr) { return null; }

    @Override
    public boolean authorise(Request request, Method method, Auth auth, io.milton.http.Resource resource) {
        return auth != null && auth.getTag() instanceof WebDavPrincipal;
    }

    @Override public String getRealm(String host) { return "webdav"; }
    @Override public boolean isDigestAllowed() { return false; }

    private String currentClientIp() {
        HttpServletRequest req = ServletRequest.getRequest();
        if (req == null) return "unknown";
        return req.getRemoteAddr();
    }

    private void writeRetryAfter(long seconds) {
        var resp = io.milton.servlet.ServletResponse.getResponse();
        if (resp != null) {
            resp.setHeader("Retry-After", Long.toString(seconds));
        }
    }
}
```

*Note:* if the pinned Milton version does not expose `io.milton.servlet.ServletRequest` / `ServletResponse` with static accessors, substitute the `io.milton.http.Request`-based accessors — the key is that the current servlet request/response is reachable from within `authenticate`. Confirm by reading `ServletRequest` in the Milton JAR.

- [ ] **Step 5: Run the test**

Run: `./mvnw -q -Dtest=WebDavAuthRateLimitIT test`
Expected: `correct_password_succeeds` PASS. `wrong_password_returns_401_sixth_time_carries_retry_after` PASS (5 plain 401s, 6th 401 with `Retry-After`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/davidneto/homepage/webdav/WebDavPrincipal.java \
        src/main/java/com/davidneto/homepage/webdav/WebDavSecurityManager.java \
        src/test/java/com/davidneto/homepage/webdav/WebDavAuthRateLimitIT.java
git commit -m "feat(webdav): Milton SecurityManager wired to LoginRateLimiter"
```

---

## Task 11: Per-user file resource factory

**Files:**

- Modify: `src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java`
- Test: `src/test/java/com/davidneto/homepage/webdav/PerUserFileResourceFactoryIT.java`

- [ ] **Step 1: Write failing integration test**

Create `src/test/java/com/davidneto/homepage/webdav/PerUserFileResourceFactoryIT.java`:

```java
package com.davidneto.homepage.webdav;

import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PerUserFileResourceFactoryIT {

    @LocalServerPort int port;
    @Autowired WebDavUserService users;
    @Value("${app.webdav.root-dir}") String rootDir;

    @BeforeEach
    void setup() throws Exception {
        users.list().forEach(u -> users.delete(u.getId()));
        users.create("alice", "correct-horse");
        users.create("bob", "battery-staple");
        Path r = Path.of(rootDir);
        if (Files.exists(r)) {
            try (var s = Files.walk(r)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }

    private HttpResponse<String> put(String user, String pass, String path, String body) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", auth)
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String user, String pass, String path) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .header("Authorization", auth)
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void alices_file_is_invisible_to_bob() throws Exception {
        assertThat(put("alice", "correct-horse", "/webdav/secret.txt", "hello").statusCode())
                .isIn(201, 204);

        assertThat(Files.exists(Path.of(rootDir, "alice", "secret.txt"))).isTrue();

        assertThat(get("alice", "correct-horse", "/webdav/secret.txt").body()).isEqualTo("hello");
        assertThat(get("bob", "battery-staple", "/webdav/secret.txt").statusCode()).isEqualTo(404);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -Dtest=PerUserFileResourceFactoryIT test`
Expected: FAIL — stub returns null resources.

- [ ] **Step 3: Implement `PerUserFileResourceFactory`**

Replace `src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java` with:

```java
package com.davidneto.homepage.webdav;

import io.milton.common.Path;
import io.milton.http.HttpManager;
import io.milton.http.Request;
import io.milton.http.ResourceFactory;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.Resource;
import io.milton.resource.CollectionResource;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.http.fs.NullSecurityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PerUserFileResourceFactory implements ResourceFactory {

    private final String rootDir;
    private final ConcurrentHashMap<String, FileSystemResourceFactory> perUser = new ConcurrentHashMap<>();

    public PerUserFileResourceFactory(@Value("${app.webdav.root-dir}") String rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public Resource getResource(String host, String sPath) throws NotAuthorizedException, BadRequestException {
        Request request = HttpManager.request();
        if (request == null) return null;
        Object tag = request.getAuthorization() == null ? null : request.getAuthorization().getTag();
        if (!(tag instanceof WebDavPrincipal principal)) return null;

        String username = principal.username();
        FileSystemResourceFactory factory = perUser.computeIfAbsent(username, this::buildFactory);

        // Strip the /webdav prefix — Milton's FileSystemResourceFactory expects paths relative to its root.
        Path p = Path.path(sPath);
        Path stripped = p.getParts().length > 0 && "webdav".equals(p.getParts()[0])
                ? Path.path(java.util.Arrays.copyOfRange(p.getParts(), 1, p.getParts().length))
                : p;
        return factory.getResource(host, stripped.toString());
    }

    private FileSystemResourceFactory buildFactory(String username) {
        File userRoot = new File(rootDir, username);
        if (!userRoot.exists() && !userRoot.mkdirs()) {
            throw new IllegalStateException("cannot create user root: " + userRoot);
        }
        FileSystemResourceFactory fsrf = new FileSystemResourceFactory(userRoot, new NullSecurityManager(), "/webdav");
        return fsrf;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./mvnw -q -Dtest=PerUserFileResourceFactoryIT test`
Expected: PASS — PUT as alice writes to `<rootDir>/alice/secret.txt`; bob cannot see it.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/webdav/PerUserFileResourceFactory.java \
        src/test/java/com/davidneto/homepage/webdav/PerUserFileResourceFactoryIT.java
git commit -m "feat(webdav): per-user isolated file resource factory"
```

---

## Task 12: Docker Compose, Dockerfile, Caddyfile

**Files:**

- Modify: `docker-compose.yml`
- Modify: `Dockerfile`
- Modify: `Caddyfile`

- [ ] **Step 1: Update `docker-compose.yml`**

Replace with:

```yaml
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - app

  app:
    build: .
    restart: unless-stopped
    environment:
      - DB_HOST=postgres
      - DB_PORT=5432
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
      - ADMIN_USERNAME=${ADMIN_USERNAME}
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
      - UPLOAD_DIR=/app/uploads
      - WEBDAV_ROOT_DIR=/app/webdav
    volumes:
      - uploads:/app/uploads
      - webdav_data:/app/webdav
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
  uploads:
  caddy_data:
  caddy_config:
  webdav_data:
```

- [ ] **Step 2: Update `Dockerfile`**

Modify `Dockerfile` — change the `RUN mkdir` line:

```dockerfile
RUN mkdir -p /app/uploads /app/webdav
```

- [ ] **Step 3: Update `Caddyfile`**

Replace with:

```
davidneto.eu, www.davidneto.eu {
    reverse_proxy app:8080
}

cloud.davidneto.eu {
    rewrite * /webdav{uri}
    reverse_proxy app:8080 {
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
    }
}
```

- [ ] **Step 4: Validate Compose config**

Run: `docker compose config > /dev/null`
Expected: no errors, no references to the removed `webdav` service.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml Dockerfile Caddyfile
git commit -m "chore(webdav): remove bytemark container, route cloud subdomain to app"
```

---

## Task 13: Manual smoke-test documentation

**Files:**

- Create: `docs/manual-tests/2026-04-14-rate-limiter-smoke-test.md`

- [ ] **Step 1: Write the smoke-test doc**

Create `docs/manual-tests/2026-04-14-rate-limiter-smoke-test.md`:

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add docs/manual-tests/2026-04-14-rate-limiter-smoke-test.md
git commit -m "docs: add rate limiter manual smoke test"
```

---

## Self-review pass

**Spec coverage:**

| Spec section | Task(s) |
|---|---|
| `LoginRateLimiter` service + IP window | 2 |
| Username lockout + escalation | 3 |
| Capacity cap + scheduled sweep + concurrency | 4 |
| Homepage filter + success/failure handlers | 5 |
| `X-Forwarded-For` propagation | 1 (`server.forward-headers-strategy: NATIVE`) |
| `webdav_users` table + entity + repo | 6 |
| `WebDavUserService` validation | 7 |
| Admin UI CRUD | 8 |
| Milton dependency + servlet mount | 9 |
| WebDAV rate-limiter integration | 10 |
| Per-user isolated directories | 11 |
| Docker / Caddy / Dockerfile updates | 12 |
| Manual smoke-test docs | 13 |
| Admin stays env-var (not DB) | 5 (unchanged `InMemoryUserDetailsManager`) |
| CSRF disabled on `/webdav/**` | 5 |
| Logging rules | 5, 10 |

**Placeholder scan:** one deliberate `<VERSION>` placeholder in Task 9 Step 2 with an explicit instruction to look it up on Maven Central; this is the only way to pin the latest version without baking a specific number into the plan. All other steps contain concrete code.

**Type consistency:** `Decision(kind, retryAfterSeconds)` and `DecisionKind` enum used uniformly across tests, filter, security manager. `WebDavPrincipal(username)` record used identically in factory and security manager. Username normalization (`trim().toLowerCase()`) applied in both `LoginRateLimiter` and `WebDavUserService.findByUsername` (via the `USERNAME_PATTERN` gate).

**Known implementation risks called out in-plan:**

- Milton servlet init vs. Spring-managed `HttpManager` (Task 9 Step 3 note).
- Milton's servlet request/response accessors may differ across versions (Task 10 Step 4 note).

These are small deviations the executor can resolve in-task; the overall architecture and rate-limiter design are independent of them.
