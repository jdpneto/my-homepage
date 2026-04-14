package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.StaticPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=password"
})
class PageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @MockitoBean
    private StaticPageService staticPageService;

    @MockitoBean
    private MarkdownService markdownService;

    @MockitoBean
    private SiteConfigService siteConfigService;

    @Test
    void viewPage_returnsPage() throws Exception {
        StaticPage page = new StaticPage();
        page.setTitle("Privacy Policy");
        page.setSlug("privacy-policy");
        page.setContent("# Privacy");
        page.setPublished(true);

        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(staticPageService.getPublishedBySlug("privacy-policy")).thenReturn(Optional.of(page));
        when(markdownService.render("# Privacy")).thenReturn("<h1>Privacy</h1>");

        mockMvc.perform(get("/pages/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "pages/view"));
    }

    @Test
    void viewPage_returns404WhenNotFound() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(staticPageService.getPublishedBySlug("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/pages/nope"))
                .andExpect(status().isNotFound());
    }
}
