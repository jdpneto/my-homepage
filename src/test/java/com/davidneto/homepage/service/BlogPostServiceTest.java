package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.repository.BlogPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogPostServiceTest {

    @Mock
    private BlogPostRepository blogPostRepository;

    @InjectMocks
    private BlogPostService blogPostService;

    private BlogPost post;

    @BeforeEach
    void setUp() {
        post = new BlogPost();
        post.setId(1L);
        post.setTitle("Test Post");
        post.setSlug("test-post");
        post.setContent("# Hello");
        post.setPublished(false);
    }

    @Test
    void getPublishedPosts_returnsPage() {
        Page<BlogPost> page = new PageImpl<>(List.of(post));
        when(blogPostRepository.findByPublishedTrueOrderByPublishedAtDesc(any()))
                .thenReturn(page);

        Page<BlogPost> result = blogPostService.getPublishedPosts(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getBySlug_returnsPost() {
        when(blogPostRepository.findBySlug("test-post")).thenReturn(Optional.of(post));

        assertThat(blogPostService.getBySlug("test-post")).isPresent();
    }

    @Test
    void save_generatesSlugFromTitle() {
        BlogPost newPost = new BlogPost();
        newPost.setTitle("My New Blog Post!");
        when(blogPostRepository.existsBySlug("my-new-blog-post")).thenReturn(false);
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost saved = blogPostService.save(newPost);

        assertThat(saved.getSlug()).isEqualTo("my-new-blog-post");
    }

    @Test
    void save_deduplicatesSlug() {
        BlogPost newPost = new BlogPost();
        newPost.setTitle("Test Post");
        when(blogPostRepository.existsBySlug("test-post")).thenReturn(true);
        when(blogPostRepository.existsBySlug("test-post-2")).thenReturn(false);
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost saved = blogPostService.save(newPost);

        assertThat(saved.getSlug()).isEqualTo("test-post-2");
    }

    @Test
    void publish_setsPublishedAtOnFirstPublish() {
        when(blogPostRepository.findById(1L)).thenReturn(Optional.of(post));
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost published = blogPostService.publish(1L);

        assertThat(published.isPublished()).isTrue();
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    void delete_removesPost() {
        when(blogPostRepository.findById(1L)).thenReturn(Optional.of(post));

        blogPostService.delete(1L);

        verify(blogPostRepository).delete(post);
    }
}
