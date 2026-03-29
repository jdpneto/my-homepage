package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SiteConfig;
import com.davidneto.homepage.repository.SiteConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteConfigServiceTest {

    @Mock
    private SiteConfigRepository siteConfigRepository;

    @InjectMocks
    private SiteConfigService siteConfigService;

    @Test
    void get_returnsValue() {
        SiteConfig config = new SiteConfig();
        config.setKey("site.name");
        config.setValue("David Neto");

        when(siteConfigRepository.findByKey("site.name")).thenReturn(Optional.of(config));

        assertThat(siteConfigService.get("site.name")).isEqualTo("David Neto");
    }

    @Test
    void get_returnsEmptyStringWhenMissing() {
        when(siteConfigRepository.findByKey("missing")).thenReturn(Optional.empty());

        assertThat(siteConfigService.get("missing")).isEmpty();
    }

    @Test
    void set_updatesExistingConfig() {
        SiteConfig config = new SiteConfig();
        config.setKey("site.name");
        config.setValue("Old Name");

        when(siteConfigRepository.findByKey("site.name")).thenReturn(Optional.of(config));
        when(siteConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        siteConfigService.set("site.name", "New Name");

        assertThat(config.getValue()).isEqualTo("New Name");
    }
}
