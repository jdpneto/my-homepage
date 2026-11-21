package com.davidneto.homepage.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * "Remember this device" for the admin: cookie {@code remember-admin = series:token} (32 random bytes
 * each, base64url), 90 days after the last use, surviving restarts (the row is in the database, and
 * nothing depends on the per-start admin password hash).
 *
 * <p>Deliberately <b>non-rotating</b>: Spring's persistent-token service issues a new token on each
 * auto-login, so two parallel requests from the phone trip its theft detection and sign out every
 * device. Here a token stays the same for its whole life; only {@code last_used_at} moves (at most
 * hourly) and the cookie is re-issued with a fresh 90-day max-age.
 *
 * <p>Only the SHA-256 of the token is stored, compared in constant time. Cookie values are never logged.
 *
 * <p>A session signed in by the cookie remembers its series ({@link #SERIES_ATTRIBUTE}); once that
 * device is forgotten ("Sign out all devices") the session ends too (see {@link ForgottenDeviceSessionFilter}).
 */
public class RememberDeviceServices extends AbstractRememberMeServices {

    public static final String COOKIE_NAME = "remember-admin";
    public static final String PARAMETER = "remember-me";
    public static final Duration LIFETIME = Duration.ofDays(90);
    private static final Duration TOUCH_EVERY = Duration.ofHours(1);
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    /** How long a "this device is still remembered" answer is trusted before asking the database again. */
    private static final Duration RECHECK_EVERY = Duration.ofSeconds(5);

    /** Session attribute: the series of the remembered device that signed this session in. */
    public static final String SERIES_ATTRIBUTE = RememberDeviceServices.class.getName() + ".SERIES";
    /** Request attribute: this request was signed in by the cookie. */
    public static final String SIGNED_IN_NOW_ATTRIBUTE = RememberDeviceServices.class.getName() + ".SIGNED_IN_NOW";

    private static final Logger log = LoggerFactory.getLogger(RememberDeviceServices.class);

    private final AdminRememberTokenRepository repo;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** series -> trusted until; guarded by itself for writes, so a revoke never leaves a stale "yes" behind. */
    private final Map<String, Instant> stillRemembered = new ConcurrentHashMap<>();
    private long generation;

    public RememberDeviceServices(String key, UserDetailsService users, AdminRememberTokenRepository repo,
                                  Clock clock, boolean secureCookie) {
        super(key, users);
        this.repo = repo;
        this.clock = clock;
        setCookieName(COOKIE_NAME);
        setParameter(PARAMETER);
        setTokenValiditySeconds((int) LIFETIME.toSeconds());
        setUseSecureCookie(secureCookie);
        setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
    }

    /** A request carrying an Authorization header (app bearer, WebDAV) is never signed in by the cookie. */
    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        if (request.getHeader("Authorization") != null) return null;
        return super.autoLogin(request, response);
    }

    @Override
    protected void onLoginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        Instant now = clock.instant();
        if (repo.deleteIdleSince(now.minus(LIFETIME)) > 0) forgetCache(); // purge long-idle devices
        forgetPresentedDevice(request);            // signing in again on this browser replaces its row

        String series = randomPart();
        String token = randomPart();
        AdminRememberToken row = new AdminRememberToken();
        row.setSeries(series);
        row.setTokenHash(hash(token));
        row.setUsername(auth.getName());
        row.setCreatedAt(now);
        row.setLastUsedAt(now);
        repo.save(row);
        setCookie(new String[] {series, token}, getTokenValiditySeconds(), request, response);
        log.info("remembered device added ip={} user={}", request.getRemoteAddr(), auth.getName());
    }

    @Override
    protected UserDetails processAutoLoginCookie(String[] parts, HttpServletRequest request,
                                                 HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        if (parts.length != 2 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches()) {
            throw refused(ip, "malformed cookie");
        }
        String series = parts[0];
        AdminRememberToken row = repo.findById(series).orElseThrow(() -> refused(ip, "unknown device"));
        if (!matches(row, parts[1])) {
            // The series alone is not a secret worth acting on: keep the row so the real device stays signed in.
            throw refused(ip, "wrong token");
        }
        Instant now = clock.instant();
        if (row.getLastUsedAt().plus(LIFETIME).isBefore(now)) {
            repo.deleteBySeries(series);
            forgetCache();
            throw refused(ip, "expired");
        }
        UserDetails user;
        try {
            user = getUserDetailsService().loadUserByUsername(row.getUsername());
        } catch (UsernameNotFoundException e) {
            throw refused(ip, "user no longer exists");
        }
        boolean admin = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(ADMIN_ROLE::equals);
        if (!admin) throw refused(ip, "not an admin");

        repo.touch(series, now, now.minus(TOUCH_EVERY));
        setCookie(new String[] {series, parts[1]}, getTokenValiditySeconds(), request, response);
        request.getSession().setAttribute(SERIES_ATTRIBUTE, series);
        request.setAttribute(SIGNED_IN_NOW_ATTRIBUTE, Boolean.TRUE);
        log.info("remembered login ip={} user={}", ip, user.getUsername());
        return user;
    }

    /** Logout forgets this browser (its row) and clears the cookie. */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        forgetPresentedDevice(request);
        super.logout(request, response, authentication);
    }

    /**
     * "Sign out all devices": forgets every remembered browser, which also ends every live session
     * those browsers signed in, except the caller's own (it is the one asking). Returns how many.
     */
    public int forgetAll(HttpServletRequest caller) {
        int n = repo.deleteEvery();
        forgetCache();
        HttpSession own = caller.getSession(false);
        if (own != null) own.removeAttribute(SERIES_ATTRIBUTE);
        return n;
    }

    /** True while the device {@code series} is remembered (answers are trusted for a few seconds). */
    public boolean isRemembered(String series) {
        Instant now = clock.instant();
        Instant until = stillRemembered.get(series);
        if (until != null && now.isBefore(until)) return true;
        long asked;
        synchronized (stillRemembered) {
            asked = generation;
        }
        boolean exists = repo.existsById(series);
        synchronized (stillRemembered) {
            if (!exists) stillRemembered.remove(series);
            else if (asked == generation) stillRemembered.put(series, now.plus(RECHECK_EVERY));
        }
        return exists;
    }

    private void forgetCache() {
        synchronized (stillRemembered) {
            generation++;
            stillRemembered.clear();
        }
    }

    /** Plain {@code series:token} (both base64url, so no escaping is needed). */
    @Override
    protected String encodeCookie(String[] tokens) {
        return String.join(":", tokens);
    }

    @Override
    protected String[] decodeCookie(String cookieValue) throws InvalidCookieException {
        return cookieValue.split(":", -1);
    }

    private void forgetPresentedDevice(HttpServletRequest request) {
        String raw = extractRememberMeCookie(request);
        if (raw == null) return;
        String[] parts = decodeCookie(raw);
        if (parts.length != 2 || !PART.matcher(parts[0]).matches()) return;
        Optional<AdminRememberToken> row = repo.findById(parts[0]);
        if (row.isPresent() && matches(row.get(), parts[1])) {
            repo.deleteBySeries(parts[0]);
            forgetCache();
        }
    }

    private RememberMeAuthenticationException refused(String ip, String reason) {
        log.warn("remembered login refused ip={} reason={}", ip, reason);
        return new RememberMeAuthenticationException("remembered login refused: " + reason);
    }

    private static boolean matches(AdminRememberToken row, String token) {
        return MessageDigest.isEqual(row.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(token).getBytes(StandardCharsets.US_ASCII));
    }

    private String randomPart() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
