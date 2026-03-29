package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class BlogController {

    private final BlogPostService blogPostService;
    private final MarkdownService markdownService;
    private final SiteConfigService siteConfigService;

    public BlogController(BlogPostService blogPostService,
                          MarkdownService markdownService,
                          SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.markdownService = markdownService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/blog")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("view", "blog/list");
        model.addAttribute("currentPage", "blog");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("posts", blogPostService.getPublishedPosts(PageRequest.of(page, 10)));
        return "layout";
    }

    @GetMapping("/blog/{slug}")
    public String post(@PathVariable String slug, Model model) {
        BlogPost post = blogPostService.getBySlug(slug)
                .filter(BlogPost::isPublished)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("view", "blog/post");
        model.addAttribute("currentPage", "blog");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("pageTitle", post.getTitle());
        model.addAttribute("post", post);
        model.addAttribute("renderedContent", markdownService.render(post.getContent()));
        return "layout";
    }
}
