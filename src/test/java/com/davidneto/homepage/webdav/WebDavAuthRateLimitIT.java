package com.davidneto.homepage.webdav;

import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebDavAuthRateLimitIT {

    @LocalServerPort int port;
    @Autowired WebDavUserService users;
    @Autowired LoginRateLimiter limiter;

    @BeforeEach
    void setup() {
        users.list().forEach(u -> users.delete(u.getId()));
        users.create("alice", "correct-horse");
        limiter.resetAllForTesting();
    }

    @AfterEach
    void teardown() {
        users.list().forEach(u -> users.delete(u.getId()));
        limiter.resetAllForTesting();
    }

    private HttpResponse<String> propfind(String user, String pass) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webdav/"))
                .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", auth)
                .header("Depth", "0")
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void wrong_password_returns_401_sixth_time_carries_retry_after() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(propfind("alice", "wrong").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = propfind("alice", "wrong");
        assertThat(blocked.statusCode()).isEqualTo(401);
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void correct_password_auth_succeeds() throws Exception {
        HttpResponse<String> ok = propfind("alice", "correct-horse");
        // 401 means auth failed — we want anything else (likely 207 or 404 at this stage).
        assertThat(ok.statusCode()).isIn(200, 207, 404);
    }
}
