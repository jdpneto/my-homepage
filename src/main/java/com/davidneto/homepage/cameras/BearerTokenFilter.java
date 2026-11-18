package com.davidneto.homepage.cameras;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code Authorization: Bearer} app tokens for the /api/cameras chain only.
 * Not a bean (so Spring Boot does not register it for every URL). Never touches the session.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

    private final CameraTokenService tokens;

    public BearerTokenFilter(CameraTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = CameraTokenService.bearer(request.getHeader("Authorization"));
        if (raw != null) {
            var auth = tokens.authenticate(raw);
            if (auth.isPresent()) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth.get());
                SecurityContextHolder.setContext(context);
            } else {
                SecurityContextHolder.clearContext(); // a bad token never falls back to a session
            }
        }
        chain.doFilter(request, response);
    }
}
