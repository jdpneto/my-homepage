package com.davidneto.homepage.gallery.webdav;

import io.milton.http.HttpManager;
import io.milton.servlet.MiltonServlet;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * A minimal Milton servlet filter that delegates to a specific {@link HttpManager}
 * supplied at construction time, bypassing {@link io.milton.servlet.SpringMiltonFilter}'s
 * hard-coded lookup of the {@code "milton.http.manager"} bean.
 */
public class GalleryDropMiltonFilter implements Filter {

    private final HttpManager httpManager;
    private ServletContext servletContext;

    GalleryDropMiltonFilter(HttpManager httpManager) {
        this.httpManager = httpManager;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        this.servletContext = filterConfig.getServletContext();
    }

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                         jakarta.servlet.ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResp = (HttpServletResponse) response;
        doMiltonProcessing(httpReq, httpResp);
    }

    private void doMiltonProcessing(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        MiltonServlet.setThreadlocals(req, resp);
        try {
            io.milton.servlet.ServletRequest miltonReq =
                    new io.milton.servlet.ServletRequest(req, servletContext);
            io.milton.servlet.ServletResponse miltonResp =
                    new io.milton.servlet.ServletResponse(resp);
            httpManager.process(miltonReq, miltonResp);
        } finally {
            MiltonServlet.clearThreadlocals();
            resp.flushBuffer();
        }
    }

    @Override
    public void destroy() {
        httpManager.shutdown();
    }
}
