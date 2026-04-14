package com.davidneto.homepage.webdav;

import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PerUserFileResourceFactoryIT {

    @LocalServerPort int port;
    @Autowired WebDavUserService users;
    @Autowired com.davidneto.homepage.security.LoginRateLimiter limiter;
    @Value("${app.webdav.root-dir}") String rootDir;

    @BeforeEach
    void setup() throws Exception {
        limiter.resetAllForTesting();
        users.list().forEach(u -> users.delete(u.getId()));
        users.create("alice", "correct-horse");
        users.create("bob", "battery-staple");
        Path r = Path.of(rootDir);
        if (Files.exists(r)) {
            try (var s = Files.walk(r)) {
                s.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        Files.createDirectories(r);
    }

    @AfterEach
    void teardown() {
        users.list().forEach(u -> users.delete(u.getId()));
        limiter.resetAllForTesting();
    }

    private HttpResponse<String> put(String user, String pass, String path, String body) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", auth)
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String user, String pass, String path) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .header("Authorization", auth)
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void alices_file_lands_in_her_dir_and_is_invisible_to_bob() throws Exception {
        HttpResponse<String> putResp = put("alice", "correct-horse", "/webdav/secret.txt", "hello");
        assertThat(putResp.statusCode()).isIn(200, 201, 204);

        assertThat(Files.exists(Path.of(rootDir, "alice", "secret.txt"))).isTrue();

        HttpResponse<String> aliceGet = get("alice", "correct-horse", "/webdav/secret.txt");
        assertThat(aliceGet.statusCode()).isEqualTo(200);
        assertThat(aliceGet.body()).isEqualTo("hello");

        HttpResponse<String> bobGet = get("bob", "battery-staple", "/webdav/secret.txt");
        assertThat(bobGet.statusCode()).isEqualTo(404);
    }
}
