package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WebDavUserServiceTest {

    @Autowired WebDavUserService service;
    @Autowired WebDavUserRepository repo;
    @Autowired PasswordEncoder encoder;
    @Value("${app.webdav.root-dir}") String rootDir;

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

    @Test
    void delete_rejects_unknown_id() {
        assertThatThrownBy(() -> service.delete(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown user id");
    }

    @Test
    void delete_wipes_user_files() throws Exception {
        WebDavUser u = service.create("erin", "password123");
        Path userDir = Path.of(rootDir, "erin");
        Files.createDirectories(userDir);
        Path file = userDir.resolve("secret.txt");
        Files.writeString(file, "private");

        service.delete(u.getId());

        assertThat(repo.findById(u.getId())).isEmpty();
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(userDir)).isFalse();
    }

    @Test
    void clear_data_wipes_files_but_keeps_user() throws Exception {
        WebDavUser u = service.create("frank", "password123");
        Path userDir = Path.of(rootDir, "frank");
        Files.createDirectories(userDir);
        Path file = userDir.resolve("note.md");
        Files.writeString(file, "hello");

        service.clearData(u.getId());

        assertThat(repo.findById(u.getId())).isPresent();
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(userDir)).isFalse();
    }

    @Test
    void clear_data_rejects_unknown_id() {
        assertThatThrownBy(() -> service.clearData(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown user id");
    }

    @Test
    void reset_password_rejects_unknown_id_before_password_validation() {
        // Short password would fail validation, but we expect the unknown-id
        // error to surface first so the endpoint doesn't leak the user absence
        // only when the password happens to be valid.
        assertThatThrownBy(() -> service.resetPassword(999_999L, "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown user id");
    }
}
