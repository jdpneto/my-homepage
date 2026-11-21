package com.davidneto.homepage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends live admin sessions after "Sign out all devices" (every session signed in before it, by
 * password or by a remembered cookie, except the caller's), and a session that a remembered device
 * signed in once that device has been forgotten, so a live session cannot outlive its cookie. Runs before the remember-me
 * filter, which then finds no session and a forgotten cookie: 401 / login page.
 * Not a bean (so Spring Boot does not register it for every URL).
 */
public class ForgottenDeviceSessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ForgottenDeviceSessionFilter.class);

    private final RememberDeviceServices rememberDevice;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ForgottenDeviceSessionFilter(RememberDeviceServices rememberDevice) {
        this.rememberDevice = rememberDevice;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication current = contexts.getContext().getAuthentication();
        if (session != null && current != null && current.isAuthenticated()
                // only a sign-in stored in this session (every real login stores one; a bearer request has none)
                && session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY) != null
                && current.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                && rememberDevice.signedOutByRevokeAll(session)) {
            // "Sign out all devices" ends every other live admin session, password or remembered.
            session.invalidate();
            contexts.clearContext();
            log.info("session ended by 'sign out all devices' ip={} user={}", request.getRemoteAddr(), current.getName());
            chain.doFilter(request, response);
            return;
        }
        if (session != null && session.getAttribute(RememberDeviceServices.SERIES_ATTRIBUTE) instanceof String series) {
            Authentication auth = contexts.getContext().getAuthentication();
            if (auth instanceof RememberMeAuthenticationToken && !rememberDevice.isRemembered(series)) {
                session.invalidate();
                contexts.clearContext();
                log.info("remembered session ended (device forgotten) ip={} user={}", request.getRemoteAddr(), auth.getName());
            }
        }
        chain.doFilter(request, response);
    }
}
