package com.davidneto.homepage.webdav;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses WebDAV DELETE and MOVE in protected folders (the camera volume).
 *
 * Runs before Milton and before authentication: it only ever denies, so not
 * checking the password here is safe. Adding files (PUT, MKCOL) stays allowed
 * because the camera uploader needs it.
 */
@Component
public class WebDavProtectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebDavProtectionFilter.class);

    private final PerUserFileResourceFactory factory;

    public WebDavProtectionFilter(PerUserFileResourceFactory factory) {
        this.factory = factory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        if ("DELETE".equalsIgnoreCase(method) || "MOVE".equalsIgnoreCase(method)) {
            String username = BasicAuthUsername.parse(request.getHeader("Authorization"));
            if (username != null && factory.isProtected(username)) {
                log.warn("refused WebDAV {} {} for protected user={}", method, request.getRequestURI(), username);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("text/plain; charset=utf-8");
                response.getWriter().write("protected folder: deleting and moving are disabled");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
