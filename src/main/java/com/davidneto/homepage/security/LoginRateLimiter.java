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

    // User counters are swept if they haven't had a failure in this long
    // AND they aren't currently locked. Not exposed as a property because
    // it's purely a memory-housekeeping knob, not a security-tunable one.
    private static final long USER_STALE_SECONDS = 3600;

    private final RateLimitProperties props;
    private final Clock clock;

    // LinkedHashMap(accessOrder=true) gives LRU semantics on get()/put().
    // All access is guarded by the per-map monitor (this class's maps are
    // synchronized on themselves).
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
        String key = normalize(username);
        if (!key.isEmpty()) {
            synchronized (userAttempts) {
                UserCounter uc = userAttempts.get(key);
                if (uc != null && uc.lockedUntil != null && uc.lockedUntil.isAfter(now)) {
                    long retry = uc.lockedUntil.getEpochSecond() - now.getEpochSecond();
                    return new Decision(DecisionKind.BLOCK_USER, Math.max(1, retry));
                }
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
        if (key.isEmpty()) return;

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
        String key = normalize(username);
        if (key.isEmpty()) return;
        synchronized (userAttempts) {
            userAttempts.remove(key);
        }
    }

    @Scheduled(fixedDelayString = "${app.rate-limit.sweep-interval-seconds}000")
    public void sweep() {
        Instant now = clock.instant();
        Instant ipCutoff = now.minusSeconds(props.ipWindowSeconds());
        Instant userCutoff = now.minusSeconds(USER_STALE_SECONDS);

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
    int ipEntryCountForTesting() {
        synchronized (ipAttempts) { return ipAttempts.size(); }
    }
    int userEntryCountForTesting() {
        synchronized (userAttempts) { return userAttempts.size(); }
    }
    public void resetAllForTesting() {
        synchronized (ipAttempts) { ipAttempts.clear(); }
        synchronized (userAttempts) { userAttempts.clear(); }
    }

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
        // Mutated and read only under synchronized (userAttempts) in the enclosing class.
        int consecutiveFailures;
        Instant lastFailure;
        Instant lockedUntil;
    }
}
