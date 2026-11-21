package com.davidneto.homepage.webdav;

import com.davidneto.homepage.cameras.ModeRequests;
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
 *   <li>Nobody may write, move, copy, delete, lock or change properties of
 *       {@value ModeRequests#FILE} either: it switches the alarm hub's mode, and only the server
 *       (on disk) may write it, so the Mac mini's upload password cannot disarm the house.
 *       Reading it (GET/PROPFIND) stays allowed: that is how the uploader picks it up.</li>
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

        // Fail closed: a Destination we cannot parse could be decoded differently by Milton, so the
        // name checks below would compare the wrong string.
        if (!READ_ONLY.contains(method) && request.getHeader("Destination") != null
                && destinationPath(request) == null) {
            forbid(response, method, request.getRequestURI(), null, "unreadable Destination header");
            return;
        }
        if (!READ_ONLY.contains(method)
                && (isMarker(decodedPath(request)) || isMarker(destinationPath(request)))) {
            forbid(response, method, request.getRequestURI(), null, "the volume marker cannot be written");
            return;
        }
        if (!READ_ONLY.contains(method)
                && (isModeRequest(decodedPath(request)) || isModeRequest(destinationPath(request)))) {
            forbid(response, method, request.getRequestURI(), null, "the mode request cannot be written over WebDAV");
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
        return lastSegmentIs(path, PerUserFileResourceFactory.VOLUME_MARKER);
    }

    private static boolean isModeRequest(String path) {
        return lastSegmentIs(path, ModeRequests.FILE);
    }

    /** Case-insensitive (a case-insensitive file system would resolve any spelling to the file). */
    private static boolean lastSegmentIs(String path, String name) {
        if (path == null) return false;
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String last = p.substring(p.lastIndexOf('/') + 1);
        return last.equalsIgnoreCase(name);
    }

    /**
     * The servlet-decoded path (no %-escapes, no ;params), each segment trimmed — the names Milton
     * will actually use: it trims a new file's or folder's name (FsDirectoryResource uses
     * String.trim), so "mode-request.json%20" would otherwise slip past and write mode-request.json.
     */
    private static String decodedPath(HttpServletRequest request) {
        String info = request.getPathInfo();
        return trimSegments(info != null ? info : request.getServletPath());
    }

    /** The decoded, trimmed Destination path; null when there is no header or it cannot be parsed. */
    private static String destinationPath(HttpServletRequest request) {
        String dest = request.getHeader("Destination");
        if (dest == null) return null;
        try {
            String path = URI.create(dest).getPath(); // decodes %-escapes
            if (path == null) return null; // opaque URI (e.g. "urn:..."): unreadable, refused by the caller
            int semi = path.indexOf(';');
            return trimSegments(semi >= 0 ? path.substring(0, semi) : path);
        } catch (IllegalArgumentException e) {
            return null; // unparseable: refused by the caller, never compared raw
        }
    }

    /** Each '/'-separated segment without leading/trailing control chars, spaces or other whitespace. */
    static String trimSegments(String path) {
        if (path == null) return null;
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().strip();
        return String.join("/", parts);
    }

    private static void forbid(HttpServletResponse response, String method, String uri, String user, String why)
            throws IOException {
        log.warn("refused WebDAV {} {} user={}: {}", method, uri, user, why);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain; charset=utf-8");
        response.getWriter().write(why);
    }
}
