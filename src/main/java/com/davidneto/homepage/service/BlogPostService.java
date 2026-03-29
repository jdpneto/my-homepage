package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.repository.BlogPostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BlogPostService {

    private final BlogPostRepository blogPostRepository;

    public BlogPostService(BlogPostRepository blogPostRepository) {
        this.blogPostRepository = blogPostRepository;
    }

    public Page<BlogPost> getPublishedPosts(Pageable pageable) {
        return blogPostRepository.findByPublishedTrueOrderByPublishedAtDesc(pageable);
    }

    public Optional<BlogPost> getBySlug(String slug) {
        return blogPostRepository.findBySlug(slug);
    }

    public Optional<BlogPost> getById(Long id) {
        return blogPostRepository.findById(id);
    }

    public List<BlogPost> getAll() {
        return blogPostRepository.findAll();
    }

    public BlogPost save(BlogPost post) {
        if (post.getSlug() == null || post.getSlug().isBlank()) {
            post.setSlug(generateSlug(post.getTitle()));
        }
        return blogPostRepository.save(post);
    }

    public BlogPost publish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(true);
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        return blogPostRepository.save(post);
    }

    public BlogPost unpublish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(false);
        return blogPostRepository.save(post);
    }

    public void delete(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        blogPostRepository.delete(post);
    }

    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String slug = base;
        int counter = 2;
        while (blogPostRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
