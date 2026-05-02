package com.davidneto.homepage.webdav;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Pre-Milton servlet filter that enforces per-user WebDAV storage quotas.
 *
 * <p>Wired in {@link MiltonConfig} on {@code /webdav/*} with order {@code 0}
 * (Milton's filter runs at order {@code 1}). For PUT requests it:
 * <ol>
 *   <li>Parses the {@code Authorization: Basic} header to identify the user.
 *       This duplicates Milton's auth parsing, but we only use the username
 *       to look up the quota — the actual credential check still runs in
 *       {@link WebDavSecurityManager} downstream, so a forged username here
 *       only affects which quota we measure against, never authorisation.</li>
 *   <li>Pre-checks {@code Content-Length} against the user's remaining
 *       budget and short-circuits with HTTP 507 when it would overflow.</li>
 *   <li>Wraps the request body in a counting {@link ServletInputStream} so
 *       chunked uploads (no {@code Content-Length}) abort with an
 *       {@link IOException} as soon as the streamed bytes exceed the
 *       remaining budget — Milton then surfaces a write failure to the
 *       client rather than silently overrunning the quota.</li>
 * </ol>
 *
 * <p>If the user header is missing or unknown the filter is a no-op and
 * defers to Milton's 401 path.
 */
@Component
public class WebDavQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebDavQuotaFilter.class);

    private final WebDavUserRepository repo;
    private final PerUserFileResourceFactory factory;

    public WebDavQuotaFilter(WebDavUserRepository repo, PerUserFileResourceFactory factory) {
        this.repo = repo;
        this.factory = factory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String username = parseBasicAuthUsername(request.getHeader("Authorization"));
        if (username == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<WebDavUser> user = repo.findByUsername(username);
        if (user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        long quotaBytes = user.get().getStorageQuotaMb() * 1024L * 1024L;
        long currentBytes;
        try {
            currentBytes = factory.currentUsageBytes(username);
        } catch (RuntimeException e) {
            log.warn("quota usage check failed for user={}: {}", username, e.toString());
            chain.doFilter(request, response);
            return;
        }
        long remaining = Math.max(0L, quotaBytes - currentBytes);
        long contentLength = request.getContentLengthLong();

        if (contentLength > 0 && contentLength > remaining) {
            log.info("webdav quota reject (content-length) user={} have={}B quota={}B incoming={}B",
                    username, currentBytes, quotaBytes, contentLength);
            sendQuotaExceeded(response, user.get().getStorageQuotaMb());
            return;
        }

        chain.doFilter(new LimitingRequest(request, remaining, username, quotaBytes), response);
    }

    private static void sendQuotaExceeded(HttpServletResponse response, long quotaMb) throws IOException {
        response.setStatus(507);
        response.setContentType("text/plain; charset=utf-8");
        response.getWriter().write("Insufficient storage: " + quotaMb + " MB quota exceeded");
    }

    /**
     * Returns the username from a Basic auth header, or {@code null} if the
     * header is missing, malformed, not Basic, or otherwise un-parseable.
     */
    private static String parseBasicAuthUsername(String header) {
        if (header == null) return null;
        String prefix = "Basic ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String b64 = header.substring(prefix.length()).trim();
        if (b64.isEmpty()) return null;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String creds = new String(decoded, StandardCharsets.UTF_8);
        int colon = creds.indexOf(':');
        if (colon <= 0) return null;
        return creds.substring(0, colon);
    }

    /**
     * Wraps the request to count bytes read from its body and throw
     * {@link IOException} once the user's remaining quota is exhausted —
     * the only viable signal we can send mid-stream from a servlet filter.
     */
    private static final class LimitingRequest extends HttpServletRequestWrapper {
        private final long limit;
        private final String username;
        private final long quotaBytes;
        private ServletInputStream cached;

        LimitingRequest(HttpServletRequest delegate, long limit, String username, long quotaBytes) {
            super(delegate);
            this.limit = limit;
            this.username = username;
            this.quotaBytes = quotaBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (cached == null) {
                cached = new LimitingStream(super.getInputStream(), limit, username, quotaBytes);
            }
            return cached;
        }
    }

    private static final class LimitingStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private final String username;
        private final long quotaBytes;
        private long read;

        LimitingStream(ServletInputStream delegate, long limit, String username, long quotaBytes) {
            this.delegate = delegate;
            this.limit = limit;
            this.username = username;
            this.quotaBytes = quotaBytes;
        }

        private void track(long n) throws IOException {
            if (n <= 0) return;
            read += n;
            if (read > limit) {
                log.info("webdav quota reject (mid-upload) user={} read={}B quota={}B",
                        username, read, quotaBytes);
                throw new IOException("WebDAV quota exceeded: " + (quotaBytes / (1024L * 1024L)) + " MB");
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) track(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) track(n);
            return n;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener rl) { delegate.setReadListener(rl); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
