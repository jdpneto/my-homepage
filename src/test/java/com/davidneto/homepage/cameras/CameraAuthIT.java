package com.davidneto.homepage.cameras;

import com.davidneto.homepage.security.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CameraAuthIT {

    @Autowired WebApplicationContext ctx;
    @Autowired LoginRateLimiter limiter;
    @Autowired CameraTokenRepository tokens;
    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        limiter.resetAllForTesting();
        tokens.deleteAll();
    }

    MvcResult login(String password) throws Exception {
        return mvc.perform(post("/api/cameras/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + password + "\",\"label\":\"iPhone\"}"))
                .andReturn();
    }

    String token() throws Exception {
        MvcResult r = login("admin");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode n = json.readTree(r.getResponse().getContentAsString());
        assertThat(n.get("expiresAt").asString()).isNotBlank();
        return n.get("token").asString();
    }

    @Test
    void tokenLoginWithAdminCredentials() throws Exception {
        assertThat(token().length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    void tokenLoginWrongPassword401() throws Exception {
        assertThat(login("nope").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void tokenLoginRateLimited429AfterFailures() throws Exception {
        for (int i = 0; i < 5; i++) login("nope-" + i);
        MvcResult r = login("admin");
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        assertThat(r.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void bearerTokenGrantsAccess() throws Exception {
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    void noAuth401NotRedirect() throws Exception {
        mvc.perform(get("/api/cameras/cameras")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSessionGrantsAccess() throws Exception {
        mvc.perform(get("/api/cameras/cameras").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void sessionPostWithoutCsrf403_bearerPostWithoutCsrfAllowed() throws Exception {
        mvc.perform(post("/api/cameras/auth/revoke-all").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cameras/auth/revoke-all").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cameras/auth/revoke-all").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    void revokedToken401() throws Exception {
        String t = token();
        mvc.perform(delete("/api/cameras/auth/token").header("Authorization", "Bearer " + t))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer " + t))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeAllKillsEveryToken() throws Exception {
        String a = token(), b = token();
        mvc.perform(post("/api/cameras/auth/revoke-all").header("Authorization", "Bearer " + a))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer " + b))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken401() throws Exception {
        String t = token();
        CameraToken row = tokens.findAll().get(0);
        row.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(row);
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer " + t))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void useSlidesExpiry() throws Exception {
        String t = token();
        CameraToken row = tokens.findAll().get(0);
        row.setExpiresAt(Instant.now().plusSeconds(3600));
        row.setLastUsedAt(Instant.now().minusSeconds(7200));
        tokens.save(row);
        mvc.perform(get("/api/cameras/cameras").header("Authorization", "Bearer " + t)).andExpect(status().isOk());
        assertThat(tokens.findAll().get(0).getExpiresAt()).isAfter(Instant.now().plusSeconds(89L * 86400));
    }

    @Test
    void tokenStoredHashedOnly() throws Exception {
        String t = token();
        assertThat(tokens.findAll()).allSatisfy(row -> {
            assertThat(row.getTokenHash()).isNotEqualTo(t).hasSize(64).matches("[0-9a-f]{64}");
        });
    }

    @Test
    void robotsHeaderPresent() throws Exception {
        mvc.perform(get("/api/cameras/cameras")).andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        mvc.perform(get("/api/cameras/cameras").with(user("admin").roles("ADMIN")))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
    }

    @Test
    void existingAdminAndMaeChainsUnaffected() throws Exception {
        mvc.perform(get("/admin/posts")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/mae")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void bearerDoesNotAuthorizeAdminPages() throws Exception {
        mvc.perform(get("/admin/posts").header("Authorization", "Bearer " + token()))
                .andExpect(status().is3xxRedirection());
    }
}
