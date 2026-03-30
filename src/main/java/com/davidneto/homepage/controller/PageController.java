package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.StaticPageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PageController {

    private final StaticPageService staticPageService;
    private final MarkdownService markdownService;
    private final SiteConfigService siteConfigService;

    public PageController(StaticPageService staticPageService,
                          MarkdownService markdownService,
                          SiteConfigService siteConfigService) {
        this.staticPageService = staticPageService;
        this.markdownService = markdownService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/pages/{slug}")
    public String view(@PathVariable String slug, Model model) {
        StaticPage page = staticPageService.getPublishedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("view", "pages/view");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("pageTitle", page.getTitle());
        model.addAttribute("page", page);
        model.addAttribute("renderedContent", markdownService.render(page.getContent()));
        return "layout";
    }
}
