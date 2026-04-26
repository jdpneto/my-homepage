package com.davidneto.homepage.gallery.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.SharedHttpSessionConfigurer.sharedHttpSession;

@SpringBootTest
@ActiveProfiles("test")
class GallerySecurityIT {

    @Autowired WebApplicationContext wac;
    @Autowired com.davidneto.homepage.security.LoginRateLimiter limiter;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        limiter.resetAllForTesting();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .apply(sharedHttpSession())
                .build();
    }

    @Test
    void unauthenticatedAccessTo_mae_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/mae"))
               .andExpect(status().is3xxRedirection())
               .andExpect(redirectedUrlPattern("**/mae/login"));
    }

    @Test
    void familyPassword_authenticatesWithGalleryContributorRole() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "testfamily").password("password", "testfamily"))
               .andExpect(authenticated().withRoles("GALLERY_CONTRIBUTOR"));
    }

    @Test
    void adminPassword_authenticatesWithBothRoles() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "admin").password("password", "admin"))
               .andExpect(authenticated().withRoles("GALLERY_CONTRIBUTOR", "GALLERY_ADMIN"));
    }

    @Test
    void wrongPassword_isUnauthenticated() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "nope").password("password", "nope"))
               .andExpect(unauthenticated());
    }
}
