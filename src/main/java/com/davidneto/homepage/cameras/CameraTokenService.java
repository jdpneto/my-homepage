package com.davidneto.homepage.cameras;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Long-lived app logins: 256-bit random tokens, stored only as SHA-256, 90 days sliding. */
@Service
public class CameraTokenService {

    public static final Duration LIFETIME = Duration.ofDays(90);
    private static final Duration TOUCH_EVERY = Duration.ofHours(1);

    public record Issued(String token, Instant expiresAt) {
    }

    private final CameraTokenRepository repo;
    private final Clock clock;
    private final String adminUsername;
    private final SecureRandom random = new SecureRandom();

    public CameraTokenService(CameraTokenRepository repo, Clock clock, @Value("${app.admin.username}") String adminUsername) {
        this.repo = repo;
        this.clock = clock;
        this.adminUsername = adminUsername;
    }

    @Transactional
    public Issued issue(String label) {
        repo.deleteByExpiresAtBefore(clock.instant()); // purge expired logins
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        CameraToken t = new CameraToken();
        t.setTokenHash(hash(raw));
        t.setLabel(label == null ? null : label.substring(0, Math.min(label.length(), 100)));
        t.setCreatedAt(now);
        t.setLastUsedAt(now);
        t.setExpiresAt(now.plus(LIFETIME));
        repo.save(t);
        return new Issued(raw, t.getExpiresAt());
    }

    @Transactional
    public Optional<Authentication> authenticate(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Optional<CameraToken> found = repo.findByTokenHash(hash(raw));
        if (found.isEmpty()) return Optional.empty();
        CameraToken t = found.get();
        Instant now = clock.instant();
        if (!t.getExpiresAt().isAfter(now)) {
            return Optional.empty(); // not deleted here: parallel requests would race on the delete
        }
        if (t.getLastUsedAt() == null || Duration.between(t.getLastUsedAt(), now).compareTo(TOUCH_EVERY) >= 0) {
            t.setLastUsedAt(now);
            t.setExpiresAt(now.plus(LIFETIME));
            repo.save(t);
        }
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(adminUsername, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Transactional
    public void revoke(String raw) {
        if (raw != null) repo.findByTokenHash(hash(raw)).ifPresent(repo::delete);
    }

    @Transactional
    public int revokeAll() {
        int n = (int) repo.count();
        repo.deleteAll();
        return n;
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token from an {@code Authorization: Bearer ...} header, or null. */
    static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = header.substring(7).trim();
        return t.isEmpty() ? null : t;
    }
}
