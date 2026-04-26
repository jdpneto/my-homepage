package com.davidneto.homepage.gallery.webdav;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.gallery.security.GalleryDropSecurityManager;
import com.davidneto.homepage.gallery.service.GalleryStorage;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.fs.FileSystemResourceFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class GalleryDropConfig {

    @Bean(name = "gallery-drop.http.manager")
    public HttpManager galleryDropHttpManager(GalleryStorage storage,
                                              GalleryProperties props,
                                              LoginRateLimiter limiter,
                                              PasswordEncoder encoder) {
        GalleryDropSecurityManager sm = new GalleryDropSecurityManager(props, limiter, encoder);
        FileSystemResourceFactory rf = new FileSystemResourceFactory(
                storage.dropDir().toFile(),
                sm,
                "gallery-drop");
        HttpManagerBuilder b = new HttpManagerBuilder();
        b.setResourceFactory(rf);
        b.setSecurityManager(sm);
        b.setEnableFormAuth(false);
        b.setEnableBasicAuth(true);
        return b.buildHttpManager();
    }

    @Bean
    public FilterRegistrationBean<GalleryDropMiltonFilter> galleryDropFilter(
            @org.springframework.beans.factory.annotation.Qualifier("gallery-drop.http.manager")
            HttpManager galleryDropHttpManager) {
        FilterRegistrationBean<GalleryDropMiltonFilter> reg =
                new FilterRegistrationBean<>(new GalleryDropMiltonFilter(galleryDropHttpManager));
        reg.addUrlPatterns("/gallery-drop/*");
        reg.setName("gallery-drop");
        reg.setOrder(2);
        return reg;
    }
}
