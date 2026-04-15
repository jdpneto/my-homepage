package com.davidneto.homepage.webdav;

import io.milton.http.Auth;
import io.milton.http.HttpManager;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.http.fs.NullSecurityManager;
import io.milton.resource.CollectionResource;
import io.milton.resource.DeletableResource;
import io.milton.resource.GetableResource;
import io.milton.resource.MakeCollectionableResource;
import io.milton.resource.PropFindableResource;
import io.milton.resource.PutableResource;
import io.milton.resource.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Per-user WebDAV root. Before auth is established, returns a proxy resource
 * that delegates basic-auth to the SecurityManager and all other operations to
 * the real per-user FileSystemResourceFactory once the principal is known.
 *
 * Note on Milton 4.0.5.2421's contextPath stripping: the internal stripContext
 * method builds the regex pattern as "/" + contextPath, so the contextPath must
 * be set WITHOUT a leading slash (i.e. "webdav") to correctly match "/webdav".
 */
@Component
public class PerUserFileResourceFactory implements ResourceFactory {

    private final SecurityManager securityManager;
    private final String rootDir;
    private final ConcurrentHashMap<String, FileSystemResourceFactory> perUser = new ConcurrentHashMap<>();

    public PerUserFileResourceFactory(@Lazy SecurityManager securityManager,
                                      @Value("${app.webdav.root-dir}") String rootDir) {
        this.securityManager = securityManager;
        this.rootDir = rootDir;
    }

    @Override
    public Resource getResource(String host, String path)
            throws NotAuthorizedException, BadRequestException {
        Request request = HttpManager.request();
        Auth auth = request == null ? null : request.getAuthorization();
        Object tag = auth == null ? null : auth.getTag();

        if (tag instanceof WebDavPrincipal principal) {
            // Auth already established — route directly to per-user factory.
            FileSystemResourceFactory factory = perUser.computeIfAbsent(principal.username(), this::buildFactory);
            return factory.getResource(host, path);
        }

        // Pre-auth (or first call for this request): return a proxy resource that
        // authenticates via the real SecurityManager and delegates all operations to
        // the per-user factory once the principal is resolved.
        return new ProxyResource(host, path);
    }

    /**
     * Recursively delete the user's root directory. Evicts the cached per-user
     * factory so a subsequent request (or a recreated user with the same name)
     * sees a fresh, empty directory.
     *
     * Defends against path traversal by resolving the candidate directory
     * canonically and verifying it is a direct child of the configured root.
     */
    public void clearUserData(String username) {
        File root = new File(rootDir);
        File userRoot = new File(root, username);
        File canonicalRoot;
        File canonicalUserRoot;
        try {
            canonicalRoot = root.getCanonicalFile();
            canonicalUserRoot = userRoot.getCanonicalFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        File userParent = canonicalUserRoot.getParentFile();
        if (userParent == null || !userParent.equals(canonicalRoot)) {
            throw new IllegalArgumentException("invalid username: " + username);
        }
        perUser.remove(username);
        if (!canonicalUserRoot.exists()) return;
        try (Stream<Path> s = Files.walk(canonicalUserRoot.toPath())) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileSystemResourceFactory buildFactory(String username) {
        File userRoot = new File(rootDir, username);
        if (!userRoot.exists() && !userRoot.mkdirs()) {
            throw new IllegalStateException("cannot create user root: " + userRoot);
        }
        // contextPath without leading slash: Milton builds regex as "/" + contextPath,
        // so "webdav" produces the correct pattern "/webdav".
        return new FileSystemResourceFactory(userRoot, new NullSecurityManager(), "webdav");
    }

    /**
     * Resolve the underlying real resource for the given path once auth.tag is a
     * WebDavPrincipal. Returns null if principal is not yet known or on error.
     */
    private Resource resolveReal(String host, String path) {
        Request req = HttpManager.request();
        Auth auth = req == null ? null : req.getAuthorization();
        Object tag = auth == null ? null : auth.getTag();
        if (!(tag instanceof WebDavPrincipal principal)) {
            return null;
        }
        FileSystemResourceFactory factory = perUser.computeIfAbsent(principal.username(), this::buildFactory);
        return factory.getResource(host, path);
    }

    /**
     * A resource proxy that:
     * - Delegates authenticate() to the real SecurityManager (so Milton can establish auth).
     * - Delegates all other operations to the per-user FileSystemResourceFactory,
     *   resolving the real resource lazily after auth.tag is set.
     *
     * Implements the full set of interfaces that Milton may call on a resource so that
     * GET, PUT, MKCOL, DELETE, and PROPFIND all work correctly without a second
     * getResource() round-trip.
     */
    private class ProxyResource
            implements CollectionResource, PropFindableResource, GetableResource,
                       PutableResource, MakeCollectionableResource, DeletableResource {

        private final String host;
        private final String path;

        ProxyResource(String host, String path) {
            this.host = host;
            this.path = path;
        }

        // ── Auth ────────────────────────────────────────────────────────────────

        @Override
        public Object authenticate(String user, String password) {
            return securityManager.authenticate(user, password);
        }

        @Override
        public boolean authorise(Request request, Request.Method method, Auth auth) {
            return auth != null && auth.getTag() instanceof WebDavPrincipal;
        }

        @Override
        public String getRealm() {
            return "webdav";
        }

        // ── Identity ────────────────────────────────────────────────────────────

        @Override
        public String getUniqueId() {
            Resource r = resolveReal(host, path);
            return r != null ? r.getUniqueId() : "proxy:" + path;
        }

        @Override
        public String getName() {
            Resource r = resolveReal(host, path);
            return r != null ? r.getName() : "";
        }

        @Override
        public Date getModifiedDate() {
            Resource r = resolveReal(host, path);
            return r instanceof PropFindableResource pfr ? pfr.getModifiedDate() : new Date(0);
        }

        @Override
        public Date getCreateDate() {
            Resource r = resolveReal(host, path);
            return r instanceof PropFindableResource pfr ? pfr.getCreateDate() : new Date(0);
        }

        @Override
        public String checkRedirect(Request request)
                throws NotAuthorizedException, BadRequestException {
            Resource r = resolveReal(host, path);
            return r != null ? r.checkRedirect(request) : null;
        }

        // ── CollectionResource ──────────────────────────────────────────────────

        @Override
        public Resource child(String childName)
                throws NotAuthorizedException, BadRequestException {
            Resource r = resolveReal(host, path);
            return r instanceof CollectionResource cr ? cr.child(childName) : null;
        }

        @Override
        public List<? extends Resource> getChildren()
                throws NotAuthorizedException, BadRequestException {
            Resource r = resolveReal(host, path);
            return r instanceof CollectionResource cr ? cr.getChildren() : Collections.emptyList();
        }

        // ── GetableResource ─────────────────────────────────────────────────────

        @Override
        public void sendContent(OutputStream out, Range range, Map<String, String> params,
                                String contentType)
                throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
            Resource r = resolveReal(host, path);
            if (r instanceof GetableResource gr) {
                gr.sendContent(out, range, params, contentType);
            } else {
                throw new NotFoundException("not found: " + path);
            }
        }

        @Override
        public Long getMaxAgeSeconds(Auth auth) {
            Resource r = resolveReal(host, path);
            return r instanceof GetableResource gr ? gr.getMaxAgeSeconds(auth) : null;
        }

        @Override
        public String getContentType(String accepts) {
            Resource r = resolveReal(host, path);
            return r instanceof GetableResource gr ? gr.getContentType(accepts) : "application/octet-stream";
        }

        @Override
        public Long getContentLength() {
            Resource r = resolveReal(host, path);
            return r instanceof GetableResource gr ? gr.getContentLength() : null;
        }

        // ── PutableResource ─────────────────────────────────────────────────────

        @Override
        public Resource createNew(String newName, InputStream inputStream, Long length, String contentType)
                throws IOException, ConflictException, NotAuthorizedException, BadRequestException {
            Resource r = resolveReal(host, path);
            if (r instanceof PutableResource pr) {
                return pr.createNew(newName, inputStream, length, contentType);
            }
            return null;
        }

        // ── MakeCollectionableResource ──────────────────────────────────────────

        @Override
        public CollectionResource createCollection(String newName)
                throws NotAuthorizedException, ConflictException, BadRequestException {
            Resource r = resolveReal(host, path);
            if (r instanceof MakeCollectionableResource mcr) {
                return mcr.createCollection(newName);
            }
            return null;
        }

        // ── DeletableResource ───────────────────────────────────────────────────

        @Override
        public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
            Resource r = resolveReal(host, path);
            if (r instanceof DeletableResource dr) {
                dr.delete();
            }
        }
    }
}
