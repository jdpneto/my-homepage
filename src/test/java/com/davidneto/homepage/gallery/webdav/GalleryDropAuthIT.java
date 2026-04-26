package com.davidneto.homepage.gallery.webdav;

import com.davidneto.homepage.security.LoginRateLimiter;
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
class GalleryDropAuthIT {

    @LocalServerPort int port;

    @Autowired LoginRateLimiter limiter;

    @BeforeEach
    void resetRateLimiter() {
        limiter.resetAllForTesting();
    }

    private HttpResponse<String> request(String method, String path, String authHeader, String body) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authHeader != null) builder.header("Authorization", authHeader);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousPropfind_returns401() throws Exception {
        HttpResponse<String> r = request("PROPFIND", "/gallery-drop/", null, null);
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void anonymousPut_returns401() throws Exception {
        HttpResponse<String> r = request("PUT", "/gallery-drop/x.txt", null, "hello");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void anonymousGet_returns401() throws Exception {
        // GET on the root collection (which always exists); Milton checks auth before serving it.
        HttpResponse<String> r = request("GET", "/gallery-drop/", null, null);
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void wrongCredentials_returns401() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("mae-drop:wrongpass".getBytes());
        HttpResponse<String> r = request("PUT", "/gallery-drop/x.txt", auth, "hello");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void wrongUsername_returns401() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("notmaedrop:testdrop".getBytes());
        HttpResponse<String> r = request("PUT", "/gallery-drop/x.txt", auth, "hello");
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    void correctCredentials_succeeds() throws Exception {
        // testdrop is the password set in src/test/resources/application-test.yml
        String auth = "Basic " + Base64.getEncoder().encodeToString("mae-drop:testdrop".getBytes());
        HttpResponse<String> r = request("PUT", "/gallery-drop/auth-it-test.txt", auth, "hello");
        assertThat(r.statusCode()).isIn(200, 201, 204);
    }
}
