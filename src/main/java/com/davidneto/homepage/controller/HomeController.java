package com.davidneto.homepage.controller;

import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.SocialLinkService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final BlogPostService blogPostService;
    private final SocialLinkService socialLinkService;
    private final SiteConfigService siteConfigService;

    public HomeController(BlogPostService blogPostService,
                          SocialLinkService socialLinkService,
                          SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.socialLinkService = socialLinkService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("view", "home");
        model.addAttribute("currentPage", "home");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        model.addAttribute("socialLinks", socialLinkService.getAllSorted());
        model.addAttribute("recentPosts", blogPostService.getPublishedPosts(PageRequest.of(0, 5)).getContent());
        return "layout";
    }
}
