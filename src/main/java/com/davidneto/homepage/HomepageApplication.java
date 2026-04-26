package com.davidneto.homepage;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.gallery.config.MaeProperties;
import com.davidneto.homepage.security.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({GalleryProperties.class, MaeProperties.class, RateLimitProperties.class})
@EnableScheduling
public class HomepageApplication {
    public static void main(String[] args) {
        SpringApplication.run(HomepageApplication.class, args);
    }
}
