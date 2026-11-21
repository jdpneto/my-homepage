package com.davidneto.homepage.cameras;

import com.davidneto.homepage.security.RememberDeviceServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * /api/cameras/**: the admin's web session (CSRF-protected) or an app bearer token (no CSRF,
 * no session). Everything except token login requires ROLE_ADMIN. 401 JSON, never a redirect.
 * A remembered admin device (cookie) signs in here too, so an API call after the session expired
 * does not show "Signed out"; never when an Authorization header is present.
 */
@Configuration
public class CameraSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain cameraApiChain(HttpSecurity http, CameraTokenService tokens,
                                              RememberDeviceServices rememberDevice) throws Exception {
        http
            .securityMatcher("/api/cameras/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/cameras/auth/token").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .addFilterBefore(new BearerTokenFilter(tokens), AnonymousAuthenticationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    request -> CameraTokenService.bearer(request.getHeader("Authorization")) != null,
                    request -> "POST".equals(request.getMethod())
                            && "/api/cameras/auth/token".equals(request.getRequestURI())))
            .rememberMe(remember -> remember
                .rememberMeServices(rememberDevice)
                .key(rememberDevice.getKey()))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"authentication required\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"forbidden\"}");
                }))
            .headers(h -> h.addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow")));
        return http.build();
    }
}
