package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.SocialLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HomeController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=password"
})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @MockitoBean
    private BlogPostService blogPostService;

    @MockitoBean
    private SocialLinkService socialLinkService;

    @MockitoBean
    private SiteConfigService siteConfigService;

    @Test
    void home_returnsHomePage() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(siteConfigService.get("site.tagline")).thenReturn("Developer");
        when(siteConfigService.get("site.photo_path")).thenReturn("");
        when(socialLinkService.getAllSorted()).thenReturn(Collections.emptyList());
        when(blogPostService.getPublishedPosts(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "home"));
    }
}
