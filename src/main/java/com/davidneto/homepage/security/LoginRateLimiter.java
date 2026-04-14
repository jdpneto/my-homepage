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
