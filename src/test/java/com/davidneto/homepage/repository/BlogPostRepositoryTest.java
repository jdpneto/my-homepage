package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.BlogPost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BlogPostRepositoryTest {

    @Autowired
    private BlogPostRepository blogPostRepository;

    @Test
    void findByPublishedTrue_returnsOnlyPublishedPosts() {
        BlogPost published = new BlogPost();
        published.setTitle("Published Post");
        published.setSlug("published-post");
        published.setPublished(true);
        published.setPublishedAt(LocalDateTime.now());
        blogPostRepository.save(published);

        BlogPost draft = new BlogPost();
        draft.setTitle("Draft Post");
        draft.setSlug("draft-post");
        draft.setPublished(false);
        blogPostRepository.save(draft);

        Page<BlogPost> result = blogPostRepository
                .findByPublishedTrueOrderByPublishedAtDesc(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSlug()).isEqualTo("published-post");
    }

    @Test
    void findBySlug_returnsPost() {
        BlogPost post = new BlogPost();
        post.setTitle("Test Post");
        post.setSlug("test-post");
        blogPostRepository.save(post);

        assertThat(blogPostRepository.findBySlug("test-post")).isPresent();
        assertThat(blogPostRepository.findBySlug("nonexistent")).isEmpty();
    }
}
