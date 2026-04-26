package com.davidneto.homepage.config;

import com.davidneto.homepage.security.LoginRateLimitFilter;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.security.RateLimitAuthenticationFailureHandler;
import com.davidneto.homepage.security.RateLimitAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Configuration
public class SecurityConfig {

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, LoginRateLimiter limiter) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/webdav/**").permitAll()
                .requestMatchers("/api/webdav/**").permitAll()
                .requestMatchers("/gallery-drop/**").permitAll()
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/admin/login")
                .successHandler(new RateLimitAuthenticationSuccessHandler(limiter))
                .failureHandler(new RateLimitAuthenticationFailureHandler(limiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/webdav/**", "/api/webdav/**", "/gallery-drop/**")
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .addFilterBefore(new LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        var user = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Allow WebDAV HTTP methods (PROPFIND, MKCOL, COPY, MOVE, LOCK, UNLOCK, PROPPATCH)
     * that Spring Security's StrictHttpFirewall blocks by default.
     */
    @Bean
    public HttpFirewall webDavHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(java.util.List.of(
                "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"
        ));
        return firewall;
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer(HttpFirewall webDavHttpFirewall) {
        return web -> web.httpFirewall(webDavHttpFirewall);
    }
}
