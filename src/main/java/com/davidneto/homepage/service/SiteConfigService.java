package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SiteConfig;
import com.davidneto.homepage.repository.SiteConfigRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;

    public SiteConfigService(SiteConfigRepository siteConfigRepository) {
        this.siteConfigRepository = siteConfigRepository;
    }

    public String get(String key) {
        return siteConfigRepository.findByKey(key)
                .map(SiteConfig::getValue)
                .orElse("");
    }

    public void set(String key, String value) {
        SiteConfig config = siteConfigRepository.findByKey(key)
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setKey(key);
                    return c;
                });
        config.setValue(value);
        siteConfigRepository.save(config);
    }
}
