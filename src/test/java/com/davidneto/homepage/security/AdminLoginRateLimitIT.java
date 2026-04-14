package com.davidneto.homepage.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AdminLoginRateLimitIT {

    @Autowired WebApplicationContext ctx;
    @Autowired LoginRateLimiter limiter;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        limiter.recordSuccess("admin"); // clear any state from prior tests
    }

    @Test
    void sixth_failed_login_returns_429() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/admin/login").with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "wrong")
                            .with(r -> { r.setRemoteAddr("1.2.3.4"); return r; }))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "wrong")
                        .with(r -> { r.setRemoteAddr("1.2.3.4"); return r; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void success_resets_counter() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/admin/login").with(csrf())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "wrong")
                            .with(r -> { r.setRemoteAddr("9.9.9.9"); return r; }))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/admin/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "admin")
                        .with(r -> { r.setRemoteAddr("9.9.9.9"); return r; }))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts"));
    }
}
