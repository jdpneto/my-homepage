package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.repository.StaticPageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StaticPageService {

    private final StaticPageRepository staticPageRepository;
    private final ImageService imageService;

    public StaticPageService(StaticPageRepository staticPageRepository, ImageService imageService) {
        this.staticPageRepository = staticPageRepository;
        this.imageService = imageService;
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

    @Transactional
    public StaticPage save(StaticPage page) {
        if (page.getSlug() == null || page.getSlug().isBlank()) {
            page.setSlug(SlugGenerator.generate(page.getTitle(), staticPageRepository::existsBySlug));
        }
        return staticPageRepository.save(page);
    }

    @Transactional
    public void delete(Long id) {
        StaticPage page = staticPageRepository.findById(id).orElseThrow();
        imageService.deleteAllByOwner(OwnerType.STATIC_PAGE, id);
        staticPageRepository.delete(page);
    }
}
