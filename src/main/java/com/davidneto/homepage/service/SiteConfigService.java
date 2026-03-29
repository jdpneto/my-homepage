package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SiteConfig;
import com.davidneto.homepage.repository.SiteConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;

    public SiteConfigService(SiteConfigRepository siteConfigRepository) {
        this.siteConfigRepository = siteConfigRepository;
    }

    public String get(String key) {
        return siteConfigRepository.findByConfigKey(key)
                .map(SiteConfig::getValue)
                .orElse("");
    }

    @Transactional
    public void set(String key, String value) {
        SiteConfig config = siteConfigRepository.findByConfigKey(key)
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setConfigKey(key);
                    return c;
                });
        config.setValue(value);
        siteConfigRepository.save(config);
    }
}
