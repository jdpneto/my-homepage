package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=testpass",
        "app.upload-dir=./uploads"
})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BlogPostService blogPostService;

    @MockitoBean
    private StaticPageService staticPageService;

    @MockitoBean
    private SocialLinkService socialLinkService;

    @MockitoBean
    private SiteConfigService siteConfigService;

    @Test
    void adminPosts_redirectsToLoginWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/admin/posts"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPosts_returnsPostList() throws Exception {
        when(blogPostService.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/posts"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/posts"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPages_returnsPageList() throws Exception {
        when(staticPageService.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/pages"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/pages"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPost_savesAndRedirects() throws Exception {
        BlogPost saved = new BlogPost();
        saved.setId(1L);
        when(blogPostService.save(any())).thenReturn(saved);

        mockMvc.perform(post("/admin/posts/new")
                        .with(csrf())
                        .param("title", "New Post")
                        .param("content", "# Hello")
                        .param("action", "save"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts/1/edit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPage_savesAndRedirects() throws Exception {
        StaticPage saved = new StaticPage();
        saved.setId(1L);
        when(staticPageService.save(any())).thenReturn(saved);

        mockMvc.perform(post("/admin/pages/new")
                        .with(csrf())
                        .param("title", "Privacy Policy")
                        .param("content", "# Privacy")
                        .param("action", "save"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pages/1/edit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePost_deletesAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/posts/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts"));

        verify(blogPostService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePage_deletesAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/pages/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pages"));

        verify(staticPageService).delete(1L);
    }
}
