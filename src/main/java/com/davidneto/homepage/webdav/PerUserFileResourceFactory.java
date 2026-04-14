package com.davidneto.homepage.webdav;

import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.CollectionResource;
import io.milton.resource.PropFindableResource;
import io.milton.resource.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Stub ResourceFactory — returns a placeholder root collection that delegates
 * authentication to the WebDavSecurityManager so Milton can perform auth checks.
 * Actual per-user filesystem routing is Task 11.
 */
@Component
public class PerUserFileResourceFactory implements ResourceFactory {

    // @Lazy to break the circular bean dependency:
    // MiltonConfig -> HttpManagerBuilder -> SecurityManager -> (nothing)
    //             -> ResourceFactory -> SecurityManager (via @Lazy)
    private final SecurityManager securityManager;

    public PerUserFileResourceFactory(@Lazy SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    @Override
    public Resource getResource(String host, String path)
            throws NotAuthorizedException, BadRequestException {
        // Return a stub root collection for any path so that Milton proceeds
        // through authentication (Task 11 will implement real routing).
        return new StubRootCollection(securityManager);
    }

    private static class StubRootCollection implements CollectionResource, PropFindableResource {
        private final SecurityManager securityManager;

        StubRootCollection(SecurityManager securityManager) {
            this.securityManager = securityManager;
        }

        @Override public String getUniqueId() { return "stub-root"; }
        @Override public String getName() { return ""; }

        @Override
        public Object authenticate(String user, String password) {
            return securityManager.authenticate(user, password);
        }

        @Override
        public boolean authorise(Request request, Request.Method method, Auth auth) {
            return auth != null && auth.getTag() instanceof WebDavPrincipal;
        }

        @Override public String getRealm() { return "webdav"; }
        @Override public Date getModifiedDate() { return new Date(0); }
        @Override public String checkRedirect(Request request) { return null; }
        @Override public Resource child(String childName) { return null; }
        @Override public List<? extends Resource> getChildren() { return Collections.emptyList(); }
        @Override public Date getCreateDate() { return new Date(0); }
    }
}
