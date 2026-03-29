package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BlogController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=password"
})
class BlogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BlogPostService blogPostService;

    @MockitoBean
    private MarkdownService markdownService;

    @MockitoBean
    private SiteConfigService siteConfigService;

    @Test
    void blogList_returnsListPage() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getPublishedPosts(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/blog"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "blog/list"));
    }

    @Test
    void blogPost_returnsPostPage() throws Exception {
        BlogPost post = new BlogPost();
        post.setTitle("Test");
        post.setSlug("test");
        post.setContent("# Hello");
        post.setPublished(true);
        post.setPublishedAt(LocalDateTime.now());

        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getBySlug("test")).thenReturn(Optional.of(post));
        when(markdownService.render("# Hello")).thenReturn("<h1>Hello</h1>");

        mockMvc.perform(get("/blog/test"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "blog/post"));
    }

    @Test
    void blogPost_returns404WhenNotFound() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getBySlug("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/blog/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
