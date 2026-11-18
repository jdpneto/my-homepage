package com.davidneto.homepage.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** /admin is the admin's alone: real form logins, not mock users. */
@SpringBootTest
@ActiveProfiles("test")
class AdminAuthIT {

    @Autowired WebApplicationContext ctx;
    @Autowired LoginRateLimiter limiter;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        limiter.resetAllForTesting();
    }

    MockHttpSession adminLogin(String user, String password) throws Exception {
        MvcResult r = mvc.perform(formLogin("/admin/login").user(user).password(password)).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    void realAdminFormLoginCanUseTheCameraApi() throws Exception {
        MockHttpSession s = adminLogin("admin", "admin");
        assertThat(mvc.perform(get("/api/cameras/cameras").session(s)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/admin/posts").session(s)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void familyPasswordCannotLogIntoAdmin() throws Exception {
        MockHttpSession s = adminLogin("whoever", "testfamily");
        int posts = mvc.perform(get("/admin/posts").session(s)).andReturn().getResponse().getStatus();
        int users = mvc.perform(get("/admin/webdav-users").session(s)).andReturn().getResponse().getStatus();
        assertThat(posts).isNotEqualTo(200);
        assertThat(users).isNotEqualTo(200);
    }

    @Test
    void adminPasswordWithWrongUsernameIsRefused() throws Exception {
        MockHttpSession s = adminLogin("not-admin", "admin");
        assertThat(mvc.perform(get("/admin/posts").session(s)).andReturn().getResponse().getStatus())
                .isNotEqualTo(200);
    }

    @Test
    void gallerySessionCannotReachAdminOrCameraApi() throws Exception {
        MvcResult r = mvc.perform(formLogin("/mae/login").user("password", "testfamily").password("password", "testfamily"))
                .andReturn();
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(mvc.perform(get("/mae").session(s)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/admin/posts").session(s)).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(mvc.perform(get("/api/cameras/cameras").session(s)).andReturn().getResponse().getStatus())
                .isIn(401, 403);
    }
}
