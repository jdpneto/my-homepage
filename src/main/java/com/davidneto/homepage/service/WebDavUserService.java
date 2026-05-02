package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import com.davidneto.homepage.webdav.PerUserFileResourceFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class WebDavUserService {

    public static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final WebDavUserRepository repo;
    private final PasswordEncoder encoder;
    private final PerUserFileResourceFactory storage;

    public WebDavUserService(WebDavUserRepository repo,
                             PasswordEncoder encoder,
                             PerUserFileResourceFactory storage) {
        this.repo = repo;
        this.encoder = encoder;
        this.storage = storage;
    }

    @Transactional
    public WebDavUser create(String username, String rawPassword) {
        return create(username, rawPassword, 50L);
    }

    @Transactional
    public WebDavUser create(String username, String rawPassword, long quotaMb) {
        validateUsername(username);
        validatePassword(rawPassword);
        validateQuota(quotaMb);
        WebDavUser u = new WebDavUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setStorageQuotaMb(quotaMb);
        return repo.save(u);
    }

    @Transactional
    public void updateQuota(Long id, long quotaMb) {
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        validateQuota(quotaMb);
        u.setStorageQuotaMb(quotaMb);
        repo.save(u);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        validatePassword(rawPassword);
        u.setPasswordHash(encoder.encode(rawPassword));
        repo.save(u);
    }

    @Transactional
    public void delete(Long id) {
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        // Wipe files first: if disk deletion fails we abort without committing
        // the DB row removal, so the admin can retry rather than end up with
        // a deleted account whose data is still on disk for the next user
        // registered under the same name.
        storage.clearUserData(u.getUsername());
        repo.deleteById(id);
    }

    @Transactional
    public void clearData(Long id) {
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        storage.clearUserData(u.getUsername());
    }

    /**
     * Wipes the named user's files. Used by the authenticated self-service
     * endpoint once the caller's WebDAV credentials have been verified; the
     * username here is the principal returned by authentication, never raw
     * request input.
     */
    public void clearDataByUsername(String username) {
        storage.clearUserData(username);
    }

    public List<WebDavUser> list() {
        return repo.findAllByOrderByUsernameAsc();
    }

    /**
     * Listing decorated with current on-disk usage. Computed by walking each
     * user's directory; cheap at small quotas, fine for the admin page which
     * is rarely loaded.
     */
    public List<UserUsageView> listWithUsage() {
        return repo.findAllByOrderByUsernameAsc().stream()
                .map(u -> new UserUsageView(u, storage.currentUsageBytes(u.getUsername())))
                .toList();
    }

    public record UserUsageView(WebDavUser user, long usageBytes) {
        public long usageMb() { return usageBytes / (1024L * 1024L); }
    }

    public Optional<WebDavUser> findByUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) return Optional.empty();
        return repo.findByUsername(username);
    }

    private void validateUsername(String u) {
        if (u == null || !USERNAME_PATTERN.matcher(u).matches())
            throw new IllegalArgumentException("username must match " + USERNAME_PATTERN.pattern());
    }

    private void validatePassword(String p) {
        if (p == null || p.length() < MIN_PASSWORD_LENGTH)
            throw new IllegalArgumentException("password must be at least " + MIN_PASSWORD_LENGTH + " chars");
    }

    private void validateQuota(long mb) {
        if (mb < 0) throw new IllegalArgumentException("quota must be >= 0 MB");
        if (mb > 1024L * 1024L) throw new IllegalArgumentException("quota must be <= 1048576 MB (1 TiB)");
    }
}
