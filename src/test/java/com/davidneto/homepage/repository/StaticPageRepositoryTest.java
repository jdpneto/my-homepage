package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.StaticPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class StaticPageRepositoryTest {

    @Autowired
    private StaticPageRepository staticPageRepository;

    @Test
    void findBySlugAndPublishedTrue_returnsOnlyPublishedPages() {
        StaticPage published = new StaticPage();
        published.setTitle("Privacy Policy");
        published.setSlug("privacy-policy");
        published.setPublished(true);
        staticPageRepository.save(published);

        StaticPage draft = new StaticPage();
        draft.setTitle("Draft Page");
        draft.setSlug("draft-page");
        draft.setPublished(false);
        staticPageRepository.save(draft);

        assertThat(staticPageRepository.findBySlugAndPublishedTrue("privacy-policy")).isPresent();
        assertThat(staticPageRepository.findBySlugAndPublishedTrue("draft-page")).isEmpty();
    }

    @Test
    void existsBySlug_checksExistence() {
        StaticPage page = new StaticPage();
        page.setTitle("Test Page");
        page.setSlug("test-page");
        staticPageRepository.save(page);

        assertThat(staticPageRepository.existsBySlug("test-page")).isTrue();
        assertThat(staticPageRepository.existsBySlug("nope")).isFalse();
    }
}
