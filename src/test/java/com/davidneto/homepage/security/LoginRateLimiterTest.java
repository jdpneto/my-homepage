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
        // different IP, different user -> allowed
        assertThat(limiter.check("5.6.7.8", "bob").kind())
                .isEqualTo(LoginRateLimiter.DecisionKind.ALLOW);
    }
}
