package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.repository.BlogPostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BlogPostService {

    private final BlogPostRepository blogPostRepository;
    private final ImageService imageService;

    public BlogPostService(BlogPostRepository blogPostRepository, ImageService imageService) {
        this.blogPostRepository = blogPostRepository;
        this.imageService = imageService;
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

    @Transactional
    public BlogPost save(BlogPost post) {
        if (post.getSlug() == null || post.getSlug().isBlank()) {
            post.setSlug(SlugGenerator.generate(post.getTitle(), blogPostRepository::existsBySlug));
        }
        return blogPostRepository.save(post);
    }

    @Transactional
    public BlogPost publish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(true);
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        return blogPostRepository.save(post);
    }

    @Transactional
    public BlogPost unpublish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(false);
        return blogPostRepository.save(post);
    }

    @Transactional
    public void delete(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        imageService.deleteAllByOwner(OwnerType.BLOG_POST, id);
        blogPostRepository.delete(post);
    }
}
