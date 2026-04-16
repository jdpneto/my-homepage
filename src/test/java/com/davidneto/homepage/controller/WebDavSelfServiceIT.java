package com.davidneto.homepage.controller;

import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebDavSelfServiceIT {

    @Autowired MockMvc mvc;
    @Autowired WebDavUserService users;
    @Autowired LoginRateLimiter limiter;
    @Value("${app.webdav.root-dir}") String rootDir;

    @BeforeEach
    void setup() throws Exception {
        limiter.resetAllForTesting();
        users.list().forEach(u -> users.delete(u.getId()));
        Path r = Path.of(rootDir);
        if (Files.exists(r)) {
            try (var s = Files.walk(r)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
        Files.createDirectories(r);
    }

    @AfterEach
    void teardown() {
        users.list().forEach(u -> users.delete(u.getId()));
        limiter.resetAllForTesting();
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    @Test
    void valid_basic_auth_wipes_user_files_and_returns_204() throws Exception {
        users.create("erin", "correct-horse");
        Path userDir = Path.of(rootDir, "erin");
        Files.createDirectories(userDir);
        Path file = userDir.resolve("secret.txt");
        Files.writeString(file, "private");

        mvc.perform(post("/api/webdav/clear-data")
                        .header("Authorization", basic("erin", "correct-horse")))
                .andExpect(status().isNoContent());

        assertThat(Files.exists(file)).isFalse();
        assertThat(users.findByUsername("erin")).isPresent();
    }

    @Test
    void wrong_password_returns_401_and_does_not_wipe_files() throws Exception {
        users.create("frank", "correct-horse");
        Path userDir = Path.of(rootDir, "frank");
        Files.createDirectories(userDir);
        Path file = userDir.resolve("note.md");
        Files.writeString(file, "keep me");

        mvc.perform(post("/api/webdav/clear-data")
                        .header("Authorization", basic("frank", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"webdav\""));

        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void unknown_user_returns_401() throws Exception {
        mvc.perform(post("/api/webdav/clear-data")
                        .header("Authorization", basic("ghost", "whatever-pass")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missing_authorization_header_returns_401() throws Exception {
        mvc.perform(post("/api/webdav/clear-data"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"webdav\""));
    }

    @Test
    void malformed_authorization_header_returns_401() throws Exception {
        mvc.perform(post("/api/webdav/clear-data")
                        .header("Authorization", "Bearer something"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/webdav/clear-data")
                        .header("Authorization", "Basic !!!not-base64!!!"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void x_forwarded_for_drives_per_ip_rate_limit_and_emits_retry_after() throws Exception {
        users.create("heidi", "correct-horse");

        // 5 failures from the same spoofed client IP -> IP tier trips. Use a
        // different username each attempt so the per-user tier never locks and
        // only the per-IP counter is responsible for the next block.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/webdav/clear-data")
                            .header("X-Forwarded-For", "203.0.113.7")
                            .header("Authorization", basic("ghost-" + i, "whatever-pass")))
                    .andExpect(status().isUnauthorized());
        }

        // Sixth attempt from the same IP is blocked before BCrypt runs; the
        // response carries Retry-After set by the security manager.
        mvc.perform(post("/api/webdav/clear-data")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .header("Authorization", basic("another-ghost", "whatever-pass")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("Retry-After"));

        // A different client IP is unaffected — heidi's per-user counter was
        // never incremented (different usernames above), so correct creds
        // succeed.
        mvc.perform(post("/api/webdav/clear-data")
                        .header("X-Forwarded-For", "198.51.100.42")
                        .header("Authorization", basic("heidi", "correct-horse")))
                .andExpect(status().isNoContent());
    }
}
