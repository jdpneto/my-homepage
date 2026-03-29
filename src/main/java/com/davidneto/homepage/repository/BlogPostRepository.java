package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.BlogPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {
    Page<BlogPost> findByPublishedTrueOrderByPublishedAtDesc(Pageable pageable);
    Optional<BlogPost> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
