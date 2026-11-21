package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CameraModeApiIT {

    @Autowired WebApplicationContext ctx;
    @Autowired CameraProperties props;
    @Autowired CameraTokenService tokens;
    MockMvc mvc;
    Path root;
    final JsonMapper json = JsonMapper.builder().build();
    final RequestPostProcessor admin = user("admin").roles("ADMIN");

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        root = props.getRootDir();
        FileSystemUtils.deleteRecursively(root);
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
    }

    @AfterEach
    void tearDown() {
        FileSystemUtils.deleteRecursively(root.toFile());
    }

    ResultActions postMode(String body, RequestPostProcessor... with) throws Exception {
        var b = post("/api/cameras/mode").contentType(MediaType.APPLICATION_JSON).content(body);
        for (RequestPostProcessor p : with) b = b.with(p);
        return mvc.perform(b);
    }

    JsonNode getMode() throws Exception {
        return json.readTree(mvc.perform(get("/api/cameras/mode").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    Path requestFile() {
        return root.resolve("mode-request.json");
    }

    @Test
    void postModeWritesRequest202() throws Exception {
        String body = postMode("{\"mode\":\"away\"}", admin, csrf()).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("mode").asString()).isEqualTo("away");
        assertThat(r.get("status").asString()).isEqualTo("pending");
        String id = r.get("id").asString();
        assertThat(id).isNotBlank();

        JsonNode file = json.readTree(Files.readString(requestFile()));
        assertThat(file.get("id").asString()).isEqualTo(id);
        assertThat(file.get("mode").asString()).isEqualTo("away");
        OffsetDateTime requested = OffsetDateTime.parse(file.get("requested_at").asString());
        OffsetDateTime expires = OffsetDateTime.parse(file.get("expires_at").asString());
        assertThat(Duration.between(requested, expires)).isEqualTo(Duration.ofMinutes(5));

        // An app device (bearer token, no CSRF) may switch too.
        String token = tokens.issue("test phone").token();
        postMode("{\"mode\":\"home\"}", r2 -> {
            r2.addHeader("Authorization", "Bearer " + token);
            return r2;
        }).andExpect(status().isAccepted());
        assertThat(json.readTree(Files.readString(requestFile())).get("mode").asString()).isEqualTo("home");
    }

    @Test
    void offAndUnknownAre400() throws Exception {
        for (String body : new String[]{"{\"mode\":\"off\"}", "{\"mode\":\"party\"}", "{}", "{\"mode\":null}",
                "{\"mode\":3}", "not json"}) {
            postMode(body, admin, csrf()).andExpect(status().isBadRequest());
        }
        assertThat(requestFile()).doesNotExist();
    }

    @Test
    void needsAdminAndCsrf() throws Exception {
        postMode("{\"mode\":\"away\"}", admin).andExpect(status().isForbidden());           // no CSRF token
        postMode("{\"mode\":\"away\"}", csrf()).andExpect(status().isUnauthorized());       // CSRF, no user
        postMode("{\"mode\":\"away\"}", user("someone").roles("USER"), csrf()).andExpect(status().isForbidden());
        mvc.perform(get("/api/cameras/mode")).andExpect(status().isUnauthorized());
        assertThat(requestFile()).doesNotExist();
    }

    @Test
    void getModeReportsCurrentAndRequest() throws Exception {
        JsonNode empty = getMode();
        assertThat(empty.get("current").isNull()).isTrue();
        assertThat(empty.get("request").isNull()).isTrue();

        Files.writeString(root.resolve("heartbeat.json"), "{\"time\":\"" + OffsetDateTime.now() + "\",\"mode\":\"home\"}");
        String id = json.readTree(postMode("{\"mode\":\"away\"}", admin, csrf()).andReturn().getResponse()
                .getContentAsString()).get("id").asString();

        JsonNode pending = getMode();
        assertThat(pending.get("current").asString()).isEqualTo("home");
        assertThat(pending.get("request").get("id").asString()).isEqualTo(id);
        assertThat(pending.get("request").get("mode").asString()).isEqualTo("away");
        assertThat(pending.get("request").get("status").asString()).isEqualTo("pending");
        assertThat(pending.get("request").get("error").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(pending.get("request").get("requested_at").asString())).isNotNull();

        Files.writeString(root.resolve("heartbeat.json"), "{\"time\":\"" + OffsetDateTime.now()
                + "\",\"mode\":\"home\",\"mode_request\":{\"id\":\"" + id
                + "\",\"status\":\"failed\",\"error\":\"hub refused\",\"at\":\"" + OffsetDateTime.now() + "\"}}");
        JsonNode failed = getMode();
        assertThat(failed.get("request").get("status").asString()).isEqualTo("failed");
        assertThat(failed.get("request").get("error").asString()).isEqualTo("hub refused");
    }

    @Test
    void volumeMissing503() throws Exception {
        Files.delete(root.resolve("volume-marker.txt"));
        postMode("{\"mode\":\"away\"}", admin, csrf()).andExpect(status().isServiceUnavailable());
        assertThat(requestFile()).doesNotExist();
    }
}
