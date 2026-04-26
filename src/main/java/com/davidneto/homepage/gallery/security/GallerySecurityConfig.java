package com.davidneto.homepage.gallery.security;

import com.davidneto.homepage.gallery.config.MaeProperties;
import com.davidneto.homepage.security.LoginRateLimitFilter;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.security.RateLimitAuthenticationFailureHandler;
import com.davidneto.homepage.security.RateLimitAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
public class GallerySecurityConfig {

    private final MaeProperties mae;
    private final String adminPasswordRaw;
    private final PasswordEncoder encoder;
    private final String maeHash;
    private final String adminHash;

    public GallerySecurityConfig(MaeProperties mae,
                                 @Value("${app.admin.password}") String adminPasswordRaw,
                                 PasswordEncoder encoder) {
        this.mae = mae;
        this.adminPasswordRaw = adminPasswordRaw;
        this.encoder = encoder;
        // Hash once at startup so we never have plaintext-versus-plaintext compare hot in memory.
        this.maeHash = mae.getPassword() == null || mae.getPassword().isBlank() ? null : encoder.encode(mae.getPassword());
        this.adminHash = encoder.encode(adminPasswordRaw);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain galleryFilterChain(HttpSecurity http, LoginRateLimiter limiter) throws Exception {
        http
            .securityMatcher("/mae/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mae/login", "/mae/css/**", "/mae/js/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/mae/api/items/*").hasRole("GALLERY_ADMIN")
                .anyRequest().hasRole("GALLERY_CONTRIBUTOR")
            )
            .formLogin(form -> form
                .loginPage("/mae/login")
                .loginProcessingUrl("/mae/login")
                .usernameParameter("password")  // Spring requires both params; we ignore username server-side
                .passwordParameter("password")
                .defaultSuccessUrl("/mae", true)
                .failureUrl("/mae/login?error")
                .successHandler(new RateLimitAuthenticationSuccessHandler(limiter))
                .failureHandler(new RateLimitAuthenticationFailureHandler(limiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/mae/logout")
                .logoutSuccessUrl("/mae/login")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
            )
            .authenticationProvider(galleryAuthenticationProvider())
            .addFilterBefore(new LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter.class)
            .headers(h -> h.addHeaderWriter((req, resp) -> {
                if (req.getRequestURI().startsWith("/mae")) resp.setHeader("X-Robots-Tag", "noindex, nofollow");
            }));

        return http.build();
    }

    @Bean
    public AuthenticationProvider galleryAuthenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) throws AuthenticationException {
                String submitted = auth.getCredentials() == null ? "" : auth.getCredentials().toString();
                if (submitted.isEmpty()) throw new BadCredentialsException("empty");

                if (encoder.matches(submitted, adminHash)) {
                    return new UsernamePasswordAuthenticationToken("admin", null, List.of(
                            new SimpleGrantedAuthority("ROLE_GALLERY_CONTRIBUTOR"),
                            new SimpleGrantedAuthority("ROLE_GALLERY_ADMIN")));
                }
                if (maeHash != null && encoder.matches(submitted, maeHash)) {
                    return new UsernamePasswordAuthenticationToken("family", null, List.of(
                            new SimpleGrantedAuthority("ROLE_GALLERY_CONTRIBUTOR")));
                }
                throw new BadCredentialsException("bad password");
            }

            @Override
            public boolean supports(Class<?> auth) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(auth);
            }
        };
    }
}
