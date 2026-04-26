package com.davidneto.homepage.gallery.security;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.SecurityManager;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.Resource;
import io.milton.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

public class GalleryDropSecurityManager implements SecurityManager {

    private static final Logger log = LoggerFactory.getLogger(GalleryDropSecurityManager.class);

    private final GalleryProperties props;
    private final LoginRateLimiter limiter;
    private final PasswordEncoder encoder;
    private final String passwordHash;

    public GalleryDropSecurityManager(GalleryProperties props, LoginRateLimiter limiter, PasswordEncoder encoder) {
        this.props = props;
        this.limiter = limiter;
        this.encoder = encoder;
        this.passwordHash = (props.getDrop().getPassword() == null || props.getDrop().getPassword().isBlank())
                ? null : encoder.encode(props.getDrop().getPassword());
    }

    @Override
    public Object authenticate(String user, String password) {
        if (passwordHash == null) return null;

        // Obtain the remote IP via Milton's thread-local servlet request binding.
        // Milton's SecurityManager.authenticate(String, String) does not receive the
        // HttpServletRequest directly; ServletRequest.getRequest() is the same pattern
        // used by WebDavSecurityManager for the same reason.
        String ip = currentClientIp();

        LoginRateLimiter.Decision decision = limiter.check(ip, user);
        if (decision.kind() != LoginRateLimiter.DecisionKind.ALLOW) {
            log.warn("gallery-drop login blocked ip={} user={} kind={} retryAfter={}s",
                    ip, user, decision.kind(), decision.retryAfterSeconds());
            return null;
        }

        if (!props.getDrop().getUsername().equals(user)) {
            limiter.recordFailure(ip, user == null ? "" : user);
            log.warn("gallery-drop login failure (wrong username) ip={} user={}", ip, user);
            return null;
        }

        if (!encoder.matches(password, passwordHash)) {
            limiter.recordFailure(ip, user);
            log.warn("gallery-drop login failure (bad password) ip={} user={}", ip, user);
            return null;
        }

        limiter.recordSuccess(user);
        log.info("gallery-drop login success ip={} user={}", ip, user);
        return user;
    }

    @Override
    public Object authenticate(DigestResponse digestRequest) { return null; }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth, Resource resource) {
        return auth != null && auth.getTag() != null;
    }

    @Override
    public String getRealm(String host) { return "gallery-drop"; }

    @Override
    public boolean isDigestAllowed() { return false; }

    private String currentClientIp() {
        HttpServletRequest req = ServletRequest.getRequest();
        return req == null ? null : req.getRemoteAddr();
    }
}
