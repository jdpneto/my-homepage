package com.davidneto.homepage.webdav;

import com.davidneto.homepage.repository.WebDavUserRepository;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.ProtectedFolderException;
import com.davidneto.homepage.service.WebDavUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Folders on the camera volume (marked by volume-marker.txt) must never be wiped. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProtectedFolderIT {

    @LocalServerPort int port;
    @Autowired WebApplicationContext ctx;
    @Autowired WebDavUserService users;
    @Autowired WebDavUserRepository repo;
    @Autowired LoginRateLimiter limiter;
    @Value("${app.webdav.root-dir}") String webdavRoot;

    MockMvc mvc;
    Path camerasRoot;
    Path clip;
    Path plainRoot;

    @BeforeEach
    void setup() throws Exception {
        limiter.resetAllForTesting();
        cleanUp();
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        users.create("cameras", "cameras-password-1", 100);
        users.create("plain", "plain-password-1", 100);
        camerasRoot = Path.of(webdavRoot, "cameras");
        plainRoot = Path.of(webdavRoot, "plain");
        Files.createDirectories(camerasRoot.resolve("Garage/2026-11-18"));
        Files.writeString(camerasRoot.resolve("volume-marker.txt"),
                "camera clips live on the 15 GB volume - do not delete\n");
        clip = camerasRoot.resolve("Garage/2026-11-18/21-45-49.mp4");
        Files.write(clip, new byte[]{1, 2, 3});
        Files.createDirectories(plainRoot);
        Files.writeString(plainRoot.resolve("a.txt"), "x");
    }

    @AfterEach
    void cleanUp() throws Exception {
        // Other ITs delete every user in their setup; unprotect first so they still can.
        Files.deleteIfExists(Path.of(webdavRoot, "cameras", "volume-marker.txt"));
        Files.deleteIfExists(Path.of(webdavRoot, "plain", "volume-marker.txt"));
        users.list().stream().filter(u -> u.getUsername().equals("cameras") || u.getUsername().equals("plain"))
                .forEach(u -> users.delete(u.getId()));
        limiter.resetAllForTesting();
    }

    private long id(String username) {
        return repo.findByUsername(username).orElseThrow().getId();
    }

    private HttpResponse<String> dav(String method, String path, String user, String pass, String body,
                                     String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webdav/" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes()));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deleteRefusesProtectedUser() {
        long camerasId = id("cameras");
        assertThrows(ProtectedFolderException.class, () -> users.delete(camerasId));
        assertThat(clip).exists();
        assertThat(camerasRoot.resolve("volume-marker.txt")).exists();
        assertThat(repo.findById(camerasId)).isPresent();
    }

    @Test
    void clearDataRefusesProtectedUser() {
        assertThrows(ProtectedFolderException.class, () -> users.clearData(id("cameras")));
        assertThat(clip).exists();
    }

    @Test
    void clearDataByUsernameRefusesProtectedUser() {
        assertThrows(ProtectedFolderException.class, () -> users.clearDataByUsername("cameras"));
        assertThat(clip).exists();
    }

    @Test
    void selfServiceClearRefusesProtectedUser() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/webdav/clear-data"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("cameras:cameras-password-1".getBytes()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(clip).exists();
    }

    @Test
    void adminDeleteAndClearOfProtectedShowErrorAndKeepFiles() throws Exception {
        long camerasId = id("cameras");
        mvc.perform(post("/admin/webdav-users/" + camerasId + "/delete").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("protected")));
        mvc.perform(post("/admin/webdav-users/" + camerasId + "/clear-data").with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("protected")));
        assertThat(clip).exists();
        assertThat(repo.findById(camerasId)).isPresent();
    }

    @Test
    void webdavDeleteAndMoveForbiddenForProtected() throws Exception {
        assertThat(dav("DELETE", "Garage/2026-11-18/21-45-49.mp4", "cameras", "cameras-password-1", null)
                .statusCode()).isEqualTo(403);
        assertThat(dav("MOVE", "Garage/2026-11-18/21-45-49.mp4", "cameras", "cameras-password-1", null,
                "Destination", "http://localhost:" + port + "/webdav/Garage/moved.mp4").statusCode()).isEqualTo(403);
        assertThat(dav("DELETE", "volume-marker.txt", "cameras", "cameras-password-1", null)
                .statusCode()).isEqualTo(403);
        assertThat(clip).exists();
        assertThat(camerasRoot.resolve("volume-marker.txt")).exists();
    }

    @Test
    void webdavPutStillAllowedForProtected() throws Exception {
        HttpResponse<String> r = dav("PUT", "heartbeat.json", "cameras", "cameras-password-1", "{}",
                "Content-Type", "application/json");
        assertThat(r.statusCode()).isIn(201, 204);
        assertThat(camerasRoot.resolve("heartbeat.json")).exists();
    }

    @Test
    void plainUserUnaffected() throws Exception {
        assertThat(dav("DELETE", "a.txt", "plain", "plain-password-1", null).statusCode()).isNotEqualTo(403);
        assertThat(plainRoot.resolve("a.txt")).doesNotExist();
        users.delete(id("plain"));
        assertThat(repo.findByUsername("plain")).isEmpty();
    }

    @Test
    void adminPageMarksProtectedAndHidesWipeButtons() throws Exception {
        long camerasId = id("cameras");
        String html = mvc.perform(get("/admin/webdav-users").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("protected");
        assertThat(html).doesNotContain("/admin/webdav-users/" + camerasId + "/delete");
        assertThat(html).doesNotContain("/admin/webdav-users/" + camerasId + "/clear-data");
        assertThat(html).contains("/admin/webdav-users/" + id("plain") + "/delete");
    }

    // --- security review follow-ups ------------------------------------------------

    @Test
    void copyOverwriteForbiddenForProtected() throws Exception {
        Files.write(camerasRoot.resolve("Garage/2026-11-18/other.mp4"), new byte[]{9});
        assertThat(dav("COPY", "Garage/2026-11-18/other.mp4", "cameras", "cameras-password-1", null,
                "Destination", "http://localhost:" + port + "/webdav/Garage/2026-11-18/21-45-49.mp4",
                "Overwrite", "T").statusCode()).isEqualTo(403);
        assertThat(Files.readAllBytes(clip)).containsExactly(1, 2, 3);
    }

    @Test
    void unparseableAuthorizationOnDeleteFailsClosed() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webdav/Garage/2026-11-18/21-45-49.mp4"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Basic Y2Ft ZXJhczpjYW1lcmFzLXBhc3N3b3JkLTE=") // space inside
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(clip).exists();
    }

    @Test
    void wrongPasswordDeleteGets401NotProtectedHint() throws Exception {
        assertThat(dav("DELETE", "Garage/2026-11-18/21-45-49.mp4", "cameras", "wrong-password", null)
                .statusCode()).isEqualTo(401);
        assertThat(clip).exists();
    }

    @Test
    void nobodyCanWriteAVolumeMarker() throws Exception {
        assertThat(dav("PUT", "volume-marker.txt", "plain", "plain-password-1", "fake").statusCode()).isEqualTo(403);
        assertThat(plainRoot.resolve("volume-marker.txt")).doesNotExist();
        assertThat(dav("MOVE", "a.txt", "plain", "plain-password-1", null,
                "Destination", "http://localhost:" + port + "/webdav/volume-marker.txt").statusCode()).isEqualTo(403);
        assertThat(dav("PUT", "volume-marker.txt", "cameras", "cameras-password-1", "x").statusCode()).isEqualTo(403);
        assertThat(Files.readString(camerasRoot.resolve("volume-marker.txt"))).startsWith("camera clips live");
    }

    @Test
    void encodedMarkerNameIsAlsoRefused() throws Exception {
        assertThat(dav("PUT", "volume%2Dmarker.txt", "plain", "plain-password-1", "fake").statusCode()).isEqualTo(403);
        assertThat(dav("PUT", "VOLUME-MARKER.TXT", "plain", "plain-password-1", "fake").statusCode()).isEqualTo(403);
        assertThat(plainRoot.resolve("volume-marker.txt")).doesNotExist();
    }

    @Test
    void lockedOutAccountGetsNoPasswordOracle() throws Exception {
        for (int i = 0; i < 12; i++) {
            dav("PROPFIND", "", "cameras", "wrong-" + i, null, "Depth", "0");
        }
        // Correct password while locked out: must look exactly like any other refused login.
        HttpResponse<String> r = dav("DELETE", "Garage/2026-11-18/21-45-49.mp4", "cameras", "cameras-password-1", null);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(r.headers().firstValue("Retry-After")).isPresent();
        assertThat(clip).exists();
    }
}
