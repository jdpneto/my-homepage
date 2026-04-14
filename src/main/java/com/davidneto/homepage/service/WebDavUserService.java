package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
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

    public WebDavUserService(WebDavUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public WebDavUser create(String username, String rawPassword) {
        validateUsername(username);
        validatePassword(rawPassword);
        WebDavUser u = new WebDavUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        return repo.save(u);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        validatePassword(rawPassword);
        WebDavUser u = repo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("unknown user id: " + id));
        u.setPasswordHash(encoder.encode(rawPassword));
        repo.save(u);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    public List<WebDavUser> list() {
        return repo.findAllByOrderByUsernameAsc();
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
}
