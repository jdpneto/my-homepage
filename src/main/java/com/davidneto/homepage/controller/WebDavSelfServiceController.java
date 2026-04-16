package com.davidneto.homepage.controller;

import com.davidneto.homepage.service.WebDavUserService;
import com.davidneto.homepage.webdav.WebDavPrincipal;
import com.davidneto.homepage.webdav.WebDavSecurityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Public self-service endpoints authenticated with WebDAV credentials.
 *
 * Reachable from the internet (unlike the ADMIN-only
 * /admin/webdav-users/** endpoints) and authenticated per-request with HTTP
 * Basic against the same {@link WebDavSecurityManager} that guards WebDAV
 * itself. That keeps brute-force rate limiting and BCrypt timing
 * equalization consistent with the WebDAV surface, and grants no capability
 * a WebDAV client doesn't already have (a user can already delete each of
 * their own files via DAV).
 */
@RestController
@RequestMapping("/api/webdav")
public class WebDavSelfServiceController {

    private static final Logger log = LoggerFactory.getLogger(WebDavSelfServiceController.class);

    private final WebDavSecurityManager securityManager;
    private final WebDavUserService users;

    public WebDavSelfServiceController(WebDavSecurityManager securityManager, WebDavUserService users) {
        this.securityManager = securityManager;
        this.users = users;
    }

    @PostMapping("/clear-data")
    public ResponseEntity<Void> clearData(HttpServletRequest request, HttpServletResponse response) {
        String[] creds = parseBasic(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (creds == null) {
            return unauthorized();
        }
        String ip = clientIp(request);
        Object principal = securityManager.authenticate(creds[0], creds[1], ip, response);
        if (!(principal instanceof WebDavPrincipal wp)) {
            // authenticate() already logged, ran BCrypt for timing
            // equalization, and set Retry-After on rate-limited responses.
            return unauthorized();
        }
        users.clearDataByUsername(wp.username());
        log.info("webdav self-service clear-data user={} ip={}", wp.username(), ip);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"webdav\"")
                .build();
    }

    private static String[] parseBasic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(header.substring(6).trim());
            String s = new String(decoded, StandardCharsets.UTF_8);
            int colon = s.indexOf(':');
            if (colon < 0) return null;
            return new String[] { s.substring(0, colon), s.substring(colon + 1) };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolve the originating client IP for rate-limiting. Prefers
     * X-Forwarded-For (Caddy overwrites it to a single value — see
     * Caddyfile — so it can be trusted in this deployment) and falls back
     * to the direct peer address. Returns null if neither is usable, so the
     * limiter skips the per-IP tier instead of bucketing on a sentinel.
     */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma < 0 ? xff : xff.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? null : remote;
    }
}
