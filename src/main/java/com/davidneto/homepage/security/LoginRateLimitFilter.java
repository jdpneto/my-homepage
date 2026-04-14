package com.davidneto.homepage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(req.getMethod()) || !"/admin/login".equals(req.getRequestURI())) {
            chain.doFilter(req, resp);
            return;
        }
        String username = req.getParameter("username");
        String ip = req.getRemoteAddr();
        LoginRateLimiter.Decision d = limiter.check(ip, username == null ? "" : username);
        if (d.kind() == LoginRateLimiter.DecisionKind.ALLOW) {
            chain.doFilter(req, resp);
            return;
        }
        resp.setStatus(429);
        resp.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
        resp.setContentType("text/plain; charset=utf-8");
        resp.getWriter().write(messageFor(d));
    }

    private static String messageFor(LoginRateLimiter.Decision d) {
        return switch (d.kind()) {
            case BLOCK_IP -> "Too many attempts. Try again in " + d.retryAfterSeconds() + " seconds.";
            case BLOCK_USER -> "This account is temporarily locked. Try again in "
                    + Math.max(1, d.retryAfterSeconds() / 60) + " minutes.";
            default -> "";
        };
    }
}
