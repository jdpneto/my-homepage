package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.repository.StaticPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaticPageServiceTest {

    @Mock
    private StaticPageRepository staticPageRepository;

    @Mock
    private ImageService imageService;

    private StaticPageService staticPageService;

    private StaticPage page;

    @BeforeEach
    void setUp() {
        staticPageService = new StaticPageService(staticPageRepository, imageService);
        page = new StaticPage();
        page.setId(1L);
        page.setTitle("Privacy Policy");
        page.setSlug("privacy-policy");
        page.setContent("# Privacy Policy\nWe respect your privacy.");
    }

    @Test
    void getPublishedBySlug_returnsPublishedPage() {
        when(staticPageRepository.findBySlugAndPublishedTrue("privacy-policy"))
                .thenReturn(Optional.of(page));

        assertThat(staticPageService.getPublishedBySlug("privacy-policy")).isPresent();
    }

    @Test
    void save_generatesSlugFromTitle() {
        StaticPage newPage = new StaticPage();
        newPage.setTitle("Terms of Service!");
        when(staticPageRepository.existsBySlug("terms-of-service")).thenReturn(false);
        when(staticPageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StaticPage saved = staticPageService.save(newPage);

        assertThat(saved.getSlug()).isEqualTo("terms-of-service");
    }

    @Test
    void delete_removesPage() {
        when(staticPageRepository.findById(1L)).thenReturn(Optional.of(page));

        staticPageService.delete(1L);

        verify(staticPageRepository).delete(page);
    }

    @Test
    void delete_cascadesImageDeletion() {
        when(staticPageRepository.findById(1L)).thenReturn(Optional.of(page));

        staticPageService.delete(1L);

        verify(imageService).deleteAllByOwner(OwnerType.STATIC_PAGE, 1L);
        verify(staticPageRepository).delete(page);
    }
}
