package com.davidneto.homepage.controller;

import com.davidneto.homepage.service.WebDavUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/webdav-users")
public class WebDavUserAdminController {

    private final WebDavUserService service;

    public WebDavUserAdminController(WebDavUserService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", service.list());
        return "admin/webdav-users";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         RedirectAttributes redirect) {
        try {
            service.create(username, password);
            redirect.addFlashAttribute("message", "User '" + username + "' created.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String password,
                                RedirectAttributes redirect) {
        try {
            service.resetPassword(id, password);
            redirect.addFlashAttribute("message", "Password reset.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.delete(id);
            redirect.addFlashAttribute("message", "User and their files deleted.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", "could not delete user files: " + e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }

    @PostMapping("/{id}/clear-data")
    public String clearData(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.clearData(id);
            redirect.addFlashAttribute("message", "User's files cleared.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", "could not clear user files: " + e.getMessage());
        }
        return "redirect:/admin/webdav-users";
    }
}
