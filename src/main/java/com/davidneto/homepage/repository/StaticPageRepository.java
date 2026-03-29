package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.StaticPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaticPageRepository extends JpaRepository<StaticPage, Long> {
    Optional<StaticPage> findBySlugAndPublishedTrue(String slug);
    Optional<StaticPage> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
