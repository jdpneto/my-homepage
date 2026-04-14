package com.davidneto.homepage.webdav;

import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.WebDavUserService;
import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.SecurityManager;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.Resource;
import io.milton.servlet.ServletRequest;
import io.milton.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class WebDavSecurityManager implements SecurityManager {

    private static final Logger log = LoggerFactory.getLogger(WebDavSecurityManager.class);

    private final WebDavUserService users;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    // Pre-computed BCrypt hash used to equalize timing when the requested user
    // does not exist. Without this, an unknown-user lookup returns in ~5ms
    // while a known-user BCrypt verify takes ~100ms, enabling username
    // enumeration via response-time analysis.
    private final String dummyPasswordHash;

    public WebDavSecurityManager(WebDavUserService users,
                                 PasswordEncoder encoder,
                                 LoginRateLimiter limiter) {
        this.users = users;
        this.encoder = encoder;
        this.limiter = limiter;
        this.dummyPasswordHash = encoder.encode("dummy-timing-equalizer");
    }

    @Override
    public Object authenticate(String user, String password) {
        String ip = currentClientIp();
        LoginRateLimiter.Decision decision = limiter.check(ip, user);
        if (decision.kind() != LoginRateLimiter.DecisionKind.ALLOW) {
            setRetryAfter(decision.retryAfterSeconds());
            log.warn("webdav login blocked ip={} user={} kind={} retryAfter={}s",
                    ip, user, decision.kind(), decision.retryAfterSeconds());
            return null;
        }
        var found = users.findByUsername(user);
        // Always run BCrypt, even for unknown users, to avoid user enumeration
        // via response timing. The dummy hash guarantees matches() returns false.
        String hash = found.isPresent() ? found.get().getPasswordHash() : dummyPasswordHash;
        boolean passwordOk = encoder.matches(password, hash);
        if (found.isEmpty() || !passwordOk) {
            limiter.recordFailure(ip, user == null ? "" : user);
            log.warn("webdav login failure ip={} user={}", ip, user);
            return null;
        }
        limiter.recordSuccess(user);
        log.info("webdav login success ip={} user={}", ip, user);
        return new WebDavPrincipal(found.get().getUsername());
    }

    @Override public Object authenticate(DigestResponse dr) { return null; }

    @Override
    public boolean authorise(Request request, Method method, Auth auth, Resource resource) {
        return auth != null && auth.getTag() instanceof WebDavPrincipal;
    }

    @Override public String getRealm(String host) { return "webdav"; }
    @Override public boolean isDigestAllowed() { return false; }

    private String currentClientIp() {
        HttpServletRequest req = ServletRequest.getRequest();
        return req == null ? "unknown" : req.getRemoteAddr();
    }

    private void setRetryAfter(long seconds) {
        HttpServletResponse resp = ServletResponse.getResponse();
        if (resp != null) resp.setHeader("Retry-After", Long.toString(seconds));
    }
}
