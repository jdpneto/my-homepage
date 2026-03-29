package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private final BlogPostService blogPostService;
    private final StaticPageService staticPageService;
    private final SocialLinkService socialLinkService;
    private final SiteConfigService siteConfigService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public AdminController(BlogPostService blogPostService,
                           StaticPageService staticPageService,
                           SocialLinkService socialLinkService,
                           SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.staticPageService = staticPageService;
        this.socialLinkService = socialLinkService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    // === Posts ===

    @GetMapping("/posts")
    public String posts(Model model) {
        model.addAttribute("posts", blogPostService.getAll());
        return "admin/posts";
    }

    @GetMapping("/posts/new")
    public String newPost(Model model) {
        model.addAttribute("post", new BlogPost());
        return "admin/post-editor";
    }

    @GetMapping("/posts/{id}/edit")
    public String editPost(@PathVariable Long id, Model model) {
        BlogPost post = blogPostService.getById(id).orElseThrow();
        model.addAttribute("post", post);
        return "admin/post-editor";
    }

    @PostMapping("/posts/new")
    public String createPost(@RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String tags,
                             @RequestParam(required = false) String excerpt,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        BlogPost post = new BlogPost();
        post.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            post.setSlug(slug);
        }
        post.setTags(tags);
        post.setExcerpt(excerpt);
        post.setContent(content);
        BlogPost saved = blogPostService.save(post);
        if ("publish".equals(action)) {
            blogPostService.publish(saved.getId());
        }
        return "redirect:/admin/posts/" + saved.getId() + "/edit";
    }

    @PostMapping("/posts/{id}/edit")
    public String updatePost(@PathVariable Long id,
                             @RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String tags,
                             @RequestParam(required = false) String excerpt,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        BlogPost post = blogPostService.getById(id).orElseThrow();
        post.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            post.setSlug(slug);
        }
        post.setTags(tags);
        post.setExcerpt(excerpt);
        post.setContent(content);
        blogPostService.save(post);
        if ("publish".equals(action)) {
            blogPostService.publish(id);
        }
        return "redirect:/admin/posts/" + id + "/edit";
    }

    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id) {
        blogPostService.delete(id);
        return "redirect:/admin/posts";
    }

    // === Pages ===

    @GetMapping("/pages")
    public String pages(Model model) {
        model.addAttribute("pages", staticPageService.getAll());
        return "admin/pages";
    }

    @GetMapping("/pages/new")
    public String newPage(Model model) {
        model.addAttribute("page", new StaticPage());
        return "admin/page-editor";
    }

    @GetMapping("/pages/{id}/edit")
    public String editPage(@PathVariable Long id, Model model) {
        StaticPage page = staticPageService.getById(id).orElseThrow();
        model.addAttribute("page", page);
        return "admin/page-editor";
    }

    @PostMapping("/pages/new")
    public String createPage(@RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        StaticPage page = new StaticPage();
        page.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            page.setSlug(slug);
        }
        page.setContent(content);
        if ("publish".equals(action)) {
            page.setPublished(true);
        }
        StaticPage saved = staticPageService.save(page);
        return "redirect:/admin/pages/" + saved.getId() + "/edit";
    }

    @PostMapping("/pages/{id}/edit")
    public String updatePage(@PathVariable Long id,
                             @RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        StaticPage page = staticPageService.getById(id).orElseThrow();
        page.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            page.setSlug(slug);
        }
        page.setContent(content);
        if ("publish".equals(action)) {
            page.setPublished(true);
        } else {
            page.setPublished(false);
        }
        staticPageService.save(page);
        return "redirect:/admin/pages/" + id + "/edit";
    }

    @PostMapping("/pages/{id}/delete")
    public String deletePage(@PathVariable Long id) {
        staticPageService.delete(id);
        return "redirect:/admin/pages";
    }

    // === Social Links ===

    @GetMapping("/social-links")
    public String socialLinks(Model model) {
        model.addAttribute("links", socialLinkService.getAllSorted());
        return "admin/social-links";
    }

    @PostMapping("/social-links")
    public String addSocialLink(@RequestParam String platform,
                                @RequestParam String displayName,
                                @RequestParam String url,
                                @RequestParam(defaultValue = "0") int sortOrder) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "redirect:/admin/social-links";
        }
        SocialLink link = new SocialLink();
        link.setPlatform(platform);
        link.setDisplayName(displayName);
        link.setUrl(url);
        link.setSortOrder(sortOrder);
        socialLinkService.save(link);
        return "redirect:/admin/social-links";
    }

    @PostMapping("/social-links/{id}/delete")
    public String deleteSocialLink(@PathVariable Long id) {
        socialLinkService.delete(id);
        return "redirect:/admin/social-links";
    }

    // === Settings ===

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        return "admin/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam String siteName,
                               @RequestParam(required = false) String siteTagline,
                               @RequestParam(required = false) MultipartFile photo,
                               Model model) throws IOException {
        siteConfigService.set("site.name", siteName);
        siteConfigService.set("site.tagline", siteTagline != null ? siteTagline : "");

        if (photo != null && !photo.isEmpty()) {
            String ext = getExtension(photo.getOriginalFilename()).toLowerCase();
            if (!ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
                model.addAttribute("error", "Unsupported image format. Allowed: jpg, jpeg, png, gif, webp");
            } else {
                Path uploadPath = Paths.get(uploadDir);
                Files.createDirectories(uploadPath);
                String filename = "photo" + ext;
                Path filePath = uploadPath.resolve(filename);
                photo.transferTo(filePath.toFile());
                siteConfigService.set("site.photo_path", "/uploads/" + filename);
            }
        }

        model.addAttribute("saved", true);
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        return "admin/settings";
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}
