package com.davidneto.homepage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A sign-in by the remember cookie rotates the CSRF token: the old XSRF-TOKEN cookie is deleted and a
 * new token is only created when something reads it. A JSON poll never does, so the page's next write
 * would carry the stale token and get 403. Reading it here writes the new XSRF-TOKEN cookie, which the
 * camera page prefers over its meta tag. Runs right after the remember-me filter. Not a bean.
 */
public class RememberedLoginCsrfFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(RememberDeviceServices.SIGNED_IN_NOW_ATTRIBUTE) != null
                && request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
