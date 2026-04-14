package com.davidneto.homepage.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

public class RateLimitAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAuthenticationFailureHandler.class);
    private final LoginRateLimiter limiter;

    public RateLimitAuthenticationFailureHandler(LoginRateLimiter limiter) {
        super("/admin/login?error");
        this.limiter = limiter;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse resp,
                                        AuthenticationException ex) throws IOException, ServletException {
        String username = req.getParameter("username");
        String ip = req.getRemoteAddr();
        limiter.recordFailure(ip, username == null ? "" : username);
        log.warn("admin login failure ip={} user={}", ip, username);
        super.onAuthenticationFailure(req, resp, ex);
    }
}
