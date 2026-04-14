package com.davidneto.homepage.webdav;

import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.ResourceFactory;
import io.milton.http.SecurityManager;
import io.milton.servlet.SpringMiltonFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MiltonConfig {

    /**
     * The bean name "milton.http.manager" is required by SpringMiltonFilter:
     * it looks up this exact name from the root WebApplicationContext when it initialises.
     */
    @Bean("milton.http.manager")
    public HttpManager miltonHttpManager(ResourceFactory resourceFactory,
                                         SecurityManager securityManager) {
        HttpManagerBuilder b = new HttpManagerBuilder();
        b.setResourceFactory(resourceFactory);
        b.setSecurityManager(securityManager);
        b.setEnableFormAuth(false);
        b.setEnableBasicAuth(true);
        return b.buildHttpManager();
    }

    /**
     * SpringMiltonFilter discovers the root Spring context via
     * WebApplicationContextUtils and then looks up "milton.http.manager".
     * No extra XML config needed.
     */
    @Bean
    public FilterRegistrationBean<SpringMiltonFilter> miltonFilter() {
        FilterRegistrationBean<SpringMiltonFilter> reg =
                new FilterRegistrationBean<>(new SpringMiltonFilter());
        reg.addUrlPatterns("/webdav/*");
        reg.setName("milton");
        reg.setOrder(1);
        return reg;
    }
}
