package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebDavUserServiceTest {

    @Autowired WebDavUserService service;
    @Autowired WebDavUserRepository repo;
    @Autowired PasswordEncoder encoder;

    @Test
    void create_persists_bcrypt_hash() {
        WebDavUser u = service.create("alice", "hunter2hunter2");
        assertThat(u.getId()).isNotNull();
        assertThat(encoder.matches("hunter2hunter2", u.getPasswordHash())).isTrue();
        assertThat(repo.findByUsername("alice")).isPresent();
    }

    @Test
    void create_rejects_invalid_username() {
        assertThatThrownBy(() -> service.create("al ice", "password1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejects_short_password() {
        assertThatThrownBy(() -> service.create("bob", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reset_password_updates_hash() {
        WebDavUser u = service.create("carol", "original-pw");
        service.resetPassword(u.getId(), "new-password-123");
        WebDavUser updated = repo.findById(u.getId()).orElseThrow();
        assertThat(encoder.matches("new-password-123", updated.getPasswordHash())).isTrue();
        assertThat(encoder.matches("original-pw", updated.getPasswordHash())).isFalse();
    }

    @Test
    void delete_removes_user() {
        WebDavUser u = service.create("dave", "password123");
        service.delete(u.getId());
        assertThat(repo.findById(u.getId())).isEmpty();
    }

    @Test
    void list_returns_alphabetical() {
        service.create("zoe", "password123");
        service.create("amy", "password123");
        assertThat(service.list()).extracting(WebDavUser::getUsername)
                .containsSubsequence("amy", "zoe");
    }
}
