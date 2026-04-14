package com.davidneto.homepage.webdav;

import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.Request.Method;
import io.milton.http.SecurityManager;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.Resource;
import org.springframework.stereotype.Component;

/**
 * Stub SecurityManager — always returns null for auth (deny all).
 * Task 10 will wire this to LoginRateLimiter and WebDavUserService.
 */
@Component
public class WebDavSecurityManager implements SecurityManager {

    @Override
    public Object authenticate(String user, String password) {
        return null;
    }

    @Override
    public Object authenticate(DigestResponse dr) {
        return null;
    }

    @Override
    public boolean authorise(Request r, Method m, Auth a, Resource res) {
        return a != null;
    }

    @Override
    public String getRealm(String host) {
        return "webdav";
    }

    @Override
    public boolean isDigestAllowed() {
        return false;
    }
}
