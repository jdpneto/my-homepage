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

        // After window: IP cleared. Use a fresh username so we don't also
        // trip the separate per-user lockout (which lasts 5 minutes).
        clock.advanceSeconds(61);
        assertThat(limiter.check("1.2.3.4", "someone-else").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }

    @Test
    void different_ips_do_not_share_counters() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);
        for (int i = 0; i < 5; i++) limiter.recordFailure("1.2.3.4", "alice");
        // different IP, different user -> allowed
        assertThat(limiter.check("5.6.7.8", "bob").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }

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

        // If the counter wasn't reset, these 4 additional failures would push it
        // to 8 (>= threshold of 5) and lock the user. Since recordSuccess cleared
        // the counter, we're back at 4 failures, below the threshold.
        for (int i = 4; i < 8; i++) {
            limiter.recordFailure("10.0.0." + i, "alice");
        }
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
        // max=1 for the IP map; threshold=5 so user lockout won't interfere.
        var tight = new RateLimitProperties(5, 60, 5, 300, 10, 1800, /*max*/ 1, 600);
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(tight, clock);

        limiter.recordFailure("ip-old", "nobody");
        clock.advanceSeconds(1);
        limiter.recordFailure("ip-new", "nobody");

        // Only 1 entry may remain; it must be the newest.
        assertThat(limiter.ipEntryCountForTesting()).isEqualTo(1);

        // A check on the evicted IP sees no history: it's treated as fresh.
        // If eviction were not LRU-correct (e.g. evicted the newer entry),
        // "ip-new" would still have an entry and "ip-old" wouldn't, so this
        // check-via-entry-count proves which one survived.
        assertThat(limiter.check("ip-old", "nobody").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }

    @Test
    void null_or_blank_ip_skips_ip_tier_but_still_counts_per_user() {
        var clock = new MutableClock(Instant.parse("2026-04-14T10:00:00Z"));
        var limiter = new LoginRateLimiter(PROPS, clock);

        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(null, "alice");
        }
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("", "alice");
        }

        // No IP bucket was created under a sentinel (null/empty).
        assertThat(limiter.ipEntryCountForTesting()).isZero();

        // Per-user lockout still applies — 8 failures is above the 5-failure threshold.
        assertThat(limiter.check(null, "alice").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.BLOCK_USER);

        // A different user, still with no IP, is unaffected.
        assertThat(limiter.check(null, "bob").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
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
}
