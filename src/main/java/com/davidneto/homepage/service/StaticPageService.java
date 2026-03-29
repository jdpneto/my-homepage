package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.repository.StaticPageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class StaticPageService {

    private final StaticPageRepository staticPageRepository;

    public StaticPageService(StaticPageRepository staticPageRepository) {
        this.staticPageRepository = staticPageRepository;
    }

    public Optional<StaticPage> getPublishedBySlug(String slug) {
        return staticPageRepository.findBySlugAndPublishedTrue(slug);
    }

    public Optional<StaticPage> getById(Long id) {
        return staticPageRepository.findById(id);
    }

    public List<StaticPage> getAll() {
        return staticPageRepository.findAll();
    }

    public StaticPage save(StaticPage page) {
        if (page.getSlug() == null || page.getSlug().isBlank()) {
            page.setSlug(generateSlug(page.getTitle()));
        }
        return staticPageRepository.save(page);
    }

    public void delete(Long id) {
        StaticPage page = staticPageRepository.findById(id).orElseThrow();
        staticPageRepository.delete(page);
    }

    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String slug = base;
        int counter = 2;
        while (staticPageRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
