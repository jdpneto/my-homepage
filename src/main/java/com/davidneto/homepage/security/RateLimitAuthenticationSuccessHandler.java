package com.davidneto.homepage.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

public class RateLimitAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAuthenticationSuccessHandler.class);
    private final LoginRateLimiter limiter;

    public RateLimitAuthenticationSuccessHandler(LoginRateLimiter limiter) {
        this.limiter = limiter;
        setDefaultTargetUrl("/admin/posts");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest req, HttpServletResponse resp,
                                        Authentication auth) throws IOException, ServletException {
        limiter.recordSuccess(auth.getName());
        log.info("admin login success ip={} user={}", req.getRemoteAddr(), auth.getName());
        super.onAuthenticationSuccess(req, resp, auth);
    }
}
