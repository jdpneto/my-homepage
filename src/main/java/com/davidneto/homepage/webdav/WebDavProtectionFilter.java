package com.davidneto.homepage.webdav;

import com.davidneto.homepage.repository.WebDavUserRepository;
import com.davidneto.homepage.security.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Guards the camera volume over WebDAV. Runs before Milton.
 *
 * <ul>
 *   <li>Nobody may write, move or copy onto {@value PerUserFileResourceFactory#VOLUME_MARKER}
 *       (it is what makes a folder protected, so users must not be able to create it).</li>
 *   <li>In a protected folder, DELETE, MOVE and COPY are refused once the password has been
 *       verified. A wrong password, or an account/IP the login rate limiter currently blocks, is
 *       passed on to Milton for its normal 401 — so this filter reveals nothing (no account
 *       enumeration, no password oracle past a lockout).</li>
 *   <li>Fails closed: a destructive request with an Authorization header this filter cannot
 *       read is refused, so a lenient parser further down cannot be used to slip past.</li>
 * </ul>
 * Adding files (PUT, MKCOL) stays allowed: the camera uploader needs it — but a PUT may not
 * write keep markers, overwrite a kept clip, or replace an existing file with an empty one.
 */
@Component
public class WebDavProtectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebDavProtectionFilter.class);
    private static final Set<String> DESTRUCTIVE = Set.of("DELETE", "MOVE", "COPY");
    private static final Set<String> READ_ONLY = Set.of("GET", "HEAD", "OPTIONS", "PROPFIND");

    private final PerUserFileResourceFactory factory;
    private final WebDavUserRepository users;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;

    public WebDavProtectionFilter(PerUserFileResourceFactory factory, WebDavUserRepository users,
                                  PasswordEncoder encoder, LoginRateLimiter limiter) {
        this.factory = factory;
        this.users = users;
        this.encoder = encoder;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);

        if (!READ_ONLY.contains(method)
                && (isMarker(decodedPath(request)) || isMarker(destinationPath(request)))) {
            forbid(response, method, request.getRequestURI(), null, "the volume marker cannot be written");
            return;
        }

        if (!READ_ONLY.contains(method)) {
            String header = request.getHeader("Authorization");
            if (header != null) {
                String[] creds = BasicAuthUsername.parseCredentials(header);
                if (creds == null) {
                    forbid(response, method, request.getRequestURI(), null, "unreadable credentials");
                    return;
                }
                LoginRateLimiter.Decision decision = limiter.check(request.getRemoteAddr(), creds[0]);
                if (decision.kind() != LoginRateLimiter.DecisionKind.ALLOW) {
                    // Answer like Milton's rate-limited 401 ourselves: never hand a blocked destructive
                    // request on (the lockout could expire between this check and Milton's).
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", "Basic realm=\"webdav\"");
                    response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                    return;
                }
                if (factory.isProtected(creds[0]) && passwordMatches(creds[0], creds[1])) {
                    String why;
                    if (DESTRUCTIVE.contains(method)) {
                        why = "protected folder: deleting, moving and copying are disabled";
                    } else if ("PUT".equals(method)) {
                        why = refusedPut(creds[0], decodedPath(request), request.getContentLengthLong());
                    } else {
                        why = isKeepName(decodedPath(request)) || isKeepName(destinationPath(request))
                                ? "keep markers cannot be written over WebDAV" : null;
                    }
                    if (why != null) {
                        forbid(response, method, request.getRequestURI(), creds[0], why);
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * In a protected folder a PUT may add files and re-upload unkept clips (the uploader's retry),
     * but never write keep markers, overwrite a kept clip, or empty an existing file.
     */
    private String refusedPut(String username, String servletPath, long contentLength) {
        String rel = servletPath == null ? "" : servletPath.replaceFirst("^/webdav/?", "");
        if (isKeepName(rel)) return "keep markers cannot be written over WebDAV";
        Path root = factory.userRoot(username);
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) return null;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path keep = target.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".keep");
        if (Files.exists(keep)) return "this clip is kept: it cannot be overwritten";
        if (contentLength == 0) return "an existing file cannot be replaced by an empty one";
        return null;
    }

    private static boolean isKeepName(String path) {
        if (path == null) return false;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return p.toLowerCase(Locale.ROOT).endsWith(".keep");
    }

    private boolean passwordMatches(String username, String password) {
        // Deliberately not the rate-limited SecurityManager: Milton authenticates (and counts) next.
        return users.findByUsername(username)
                .map(u -> encoder.matches(password, u.getPasswordHash()))
                .orElse(false);
    }

    private static boolean isMarker(String path) {
        if (path == null) return false;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String last = p.substring(p.lastIndexOf('/') + 1);
        return last.equalsIgnoreCase(PerUserFileResourceFactory.VOLUME_MARKER);
    }

    /** The servlet-decoded path (no %-escapes, no ;params) — the name Milton will actually use. */
    private static String decodedPath(HttpServletRequest request) {
        String info = request.getPathInfo();
        return info != null ? info : request.getServletPath();
    }

    private static String destinationPath(HttpServletRequest request) {
        String dest = request.getHeader("Destination");
        if (dest == null) return null;
        try {
            String path = URI.create(dest).getPath(); // decodes %-escapes
            if (path == null) return dest;
            int semi = path.indexOf(';');
            return semi >= 0 ? path.substring(0, semi) : path;
        } catch (IllegalArgumentException e) {
            return dest;
        }
    }

    private static void forbid(HttpServletResponse response, String method, String uri, String user, String why)
            throws IOException {
        log.warn("refused WebDAV {} {} user={}: {}", method, uri, user, why);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain; charset=utf-8");
        response.getWriter().write(why);
    }
}
