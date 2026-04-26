package com.davidneto.homepage.gallery.webdav;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.gallery.security.GalleryDropSecurityManager;
import com.davidneto.homepage.gallery.service.GalleryStorage;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.http.fs.NullSecurityManager;
import io.milton.servlet.SpringMiltonFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class GalleryDropConfig {

    /**
     * The bean name "gallery-drop.http.manager" is used by the second SpringMiltonFilter.
     * SpringMiltonFilter looks up the manager bean by name from its init-param
     * "milton.http.manager.bean".
     */
    @Bean(name = "gallery-drop.http.manager")
    public HttpManager galleryDropHttpManager(GalleryStorage storage,
                                              GalleryProperties props,
                                              LoginRateLimiter limiter,
                                              PasswordEncoder encoder) {
        FileSystemResourceFactory rf = new FileSystemResourceFactory(
                storage.dropDir().toFile(),
                new NullSecurityManager(),
                "gallery-drop");
        GalleryDropSecurityManager sm = new GalleryDropSecurityManager(props, limiter, encoder);
        HttpManagerBuilder b = new HttpManagerBuilder();
        b.setResourceFactory(rf);
        b.setSecurityManager(sm);
        b.setEnableFormAuth(false);
        b.setEnableBasicAuth(true);
        return b.buildHttpManager();
    }

    @Bean
    public FilterRegistrationBean<SpringMiltonFilter> galleryDropFilter() {
        FilterRegistrationBean<SpringMiltonFilter> reg =
                new FilterRegistrationBean<>(new SpringMiltonFilter());
        reg.addUrlPatterns("/gallery-drop/*");
        reg.setName("gallery-drop");
        reg.setOrder(2);
        // Tell this filter instance to look up the named bean (not the default "milton.http.manager"):
        reg.addInitParameter("milton.http.manager.bean", "gallery-drop.http.manager");
        return reg;
    }
}
