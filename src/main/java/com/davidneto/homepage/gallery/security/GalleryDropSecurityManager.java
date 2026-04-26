package com.davidneto.homepage.gallery.security;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.SecurityManager;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;

public class GalleryDropSecurityManager implements SecurityManager {

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
        if (!props.getDrop().getUsername().equals(user)) return null;
        if (!encoder.matches(password, passwordHash)) return null;
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
}
