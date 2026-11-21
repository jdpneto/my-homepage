package com.davidneto.homepage.security;

import com.davidneto.homepage.cameras.CameraTokenRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** "Remember this device": a 90-day rolling admin login that survives deploys. Real form logins only. */
@SpringBootTest
@ActiveProfiles("test")
class RememberDeviceIT {

    static final String COOKIE = "remember-admin";
    static final int NINETY_DAYS = 90 * 24 * 3600;

    @Autowired WebApplicationContext ctx;
    @Autowired LoginRateLimiter limiter;
    @Autowired AdminRememberTokenRepository remembered;
    @Autowired CameraTokenRepository appTokens;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        limiter.resetAllForTesting();
        remembered.deleteAll();
        appTokens.deleteAll();
    }

    MvcResult login(boolean remember, MockHttpSession session) throws Exception {
        var req = post("/admin/login").param("username", "admin").param("password", "admin").with(csrf());
        if (remember) req.param("remember-me", "on");
        if (session != null) req.session(session);
        return mvc.perform(req).andReturn();
    }

    Cookie rememberedDevice() throws Exception {
        MvcResult r = login(true, null);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/admin/posts");
        Cookie c = r.getResponse().getCookie(COOKIE);
        assertThat(c).as("remember cookie set").isNotNull();
        return new Cookie(COOKIE, c.getValue());
    }

    /** A fresh browser request (no session) carrying only the cookie. */
    MockHttpServletResponse withCookie(String url, Cookie c) throws Exception {
        return mvc.perform(get(url).cookie(c)).andReturn().getResponse();
    }

    boolean signedIn(Cookie c) throws Exception {
        MockHttpServletResponse r = withCookie("/admin/posts", c);
        if (r.getStatus() == 200) return true;
        assertThat(r.getRedirectedUrl()).endsWith("/admin/login");
        return false;
    }

    static boolean cleared(MockHttpServletResponse r) {
        Cookie c = r.getCookie(COOKIE);
        return c != null && c.getMaxAge() == 0;
    }

    void setLastUsed(Instant when) {
        remembered.findAll().forEach(row -> {
            row.setLastUsedAt(when);
            remembered.save(row);
        });
    }

    // ------------------------------------------------------------------ login form

    @Test
    void loginPageRemembersByDefault() throws Exception {
        String html = mvc.perform(get("/admin/login")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("name=\"remember-me\"").contains("Remember this device for 90 days");
        int box = html.indexOf("name=\"remember-me\"");
        String tag = html.substring(html.lastIndexOf('<', box), html.indexOf('>', box) + 1);
        assertThat(tag).contains("type=\"checkbox\"").contains("checked");
    }

    @Test
    void rememberCookieIsHttpOnlySecureLaxAndStoredHashed() throws Exception {
        MvcResult r = login(true, null);
        Cookie c = r.getResponse().getCookie(COOKIE);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSecure()).isTrue();
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getMaxAge()).isEqualTo(NINETY_DAYS);
        assertThat(c.getValue()).matches("[A-Za-z0-9_-]{43}:[A-Za-z0-9_-]{43}");
        // MockMvc's Set-Cookie rendering drops generic attributes; Tomcat writes them out.
        assertThat(c.getAttribute("SameSite")).isEqualTo("Lax");

        String[] parts = c.getValue().split(":");
        assertThat(remembered.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSeries()).isEqualTo(parts[0]);
            assertThat(row.getTokenHash()).matches("[0-9a-f]{64}").isNotEqualTo(parts[1]);
            assertThat(row.getUsername()).isEqualTo("admin");
        });
    }

    @Test
    void loginWithoutCheckboxSetsNoCookie() throws Exception {
        MvcResult r = login(false, null);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/admin/posts");
        Cookie c = r.getResponse().getCookie(COOKIE);
        assertThat(c == null || c.getMaxAge() == 0).isTrue();
        assertThat(remembered.count()).isZero();
    }

    @Test
    void loginReturnsToRequestedPage() throws Exception {
        MvcResult first = mvc.perform(get("/admin/cameras")).andReturn();
        assertThat(first.getResponse().getRedirectedUrl()).endsWith("/admin/login");
        MockHttpSession s = (MockHttpSession) first.getRequest().getSession(false);
        String target = login(true, s).getResponse().getRedirectedUrl();
        assertThat(target).contains("/admin/cameras");
        // Nothing requested: Posts, as before.
        assertThat(login(false, null).getResponse().getRedirectedUrl()).isEqualTo("/admin/posts");
    }

    // ------------------------------------------------------------------ auto-login

    @Test
    void rememberedDeviceSignsInWithoutASession() throws Exception {
        Cookie c = rememberedDevice();
        assertThat(signedIn(c)).isTrue();
        // the camera API too (an API call after the session expired must not show "Signed out")
        assertThat(withCookie("/api/cameras/cameras", c).getStatus()).isEqualTo(200);
    }

    @Test
    void parallelAutoLoginsDoNotSignOut() throws Exception {
        Cookie c = rememberedDevice();
        setLastUsed(Instant.now().minus(Duration.ofHours(2))); // every request wants to roll it forward
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String url = i % 2 == 0 ? "/admin/posts" : "/api/cameras/cameras";
                results.add(pool.submit(() -> {
                    go.await();
                    return withCookie(url, c).getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> f : results) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(signedIn(c)).isTrue();
        assertThat(remembered.count()).isEqualTo(1);
    }

    @Test
    void rememberedLoginSurvivesRestart() throws Exception {
        Cookie c = rememberedDevice();
        // A deploy: new key, a freshly hashed admin password, the same database.
        var users = new InMemoryUserDetailsManager(User.withUsername("admin")
                .password(new BCryptPasswordEncoder().encode("admin")).roles("ADMIN").build());
        var fresh = new RememberDeviceServices("a-new-key", users, remembered, Clock.systemUTC(), true);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/cameras");
        req.setCookies(c);
        Authentication a = fresh.autoLogin(req, new MockHttpServletResponse());
        assertThat(a).isNotNull();
        assertThat(a.getName()).isEqualTo("admin");
        assertThat(a.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_ADMIN");
        assertThat(signedIn(c)).isTrue();
    }

    @Test
    void lastUsedRolledForward() throws Exception {
        Cookie c = rememberedDevice();
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        setLastUsed(tenMinutesAgo);
        assertThat(signedIn(c)).isTrue();
        // at most one write an hour
        assertThat(remembered.findAll().get(0).getLastUsedAt()).isEqualTo(tenMinutesAgo);

        setLastUsed(Instant.now().minus(Duration.ofHours(2)));
        MockHttpServletResponse r = withCookie("/admin/posts", c);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(remembered.findAll().get(0).getLastUsedAt()).isAfter(Instant.now().minusSeconds(60));
        Cookie reissued = r.getCookie(COOKIE);
        assertThat(reissued).as("cookie re-issued on use").isNotNull();
        assertThat(reissued.getValue()).isEqualTo(c.getValue()); // non-rotating
        assertThat(reissued.getMaxAge()).isEqualTo(NINETY_DAYS);
    }

    @Test
    void expiredAfter90DaysIdle() throws Exception {
        Cookie c = rememberedDevice();
        setLastUsed(Instant.now().minus(Duration.ofDays(89)));
        assertThat(signedIn(c)).isTrue();

        setLastUsed(Instant.now().minus(Duration.ofDays(91)));
        MockHttpServletResponse r = withCookie("/admin/posts", c);
        assertThat(r.getRedirectedUrl()).endsWith("/admin/login");
        assertThat(cleared(r)).isTrue();
        assertThat(remembered.count()).isZero();
    }

    @Test
    void wrongTokenIsRefused() throws Exception {
        Cookie a = rememberedDevice();
        Cookie b = rememberedDevice();
        String series = a.getValue().split(":")[0];
        String forged = series + ":" + "A".repeat(43);
        MockHttpServletResponse r = withCookie("/admin/posts", new Cookie(COOKIE, forged));
        assertThat(r.getRedirectedUrl()).endsWith("/admin/login");
        assertThat(cleared(r)).isTrue();
        // the real devices are unaffected
        assertThat(signedIn(a)).isTrue();
        assertThat(signedIn(b)).isTrue();
        assertThat(remembered.count()).isEqualTo(2);
    }

    @Test
    void malformedOrUnknownCookieIsRefused() throws Exception {
        for (String v : List.of("garbage", "a:b:c", "A".repeat(43) + ":" + "B".repeat(43))) {
            MockHttpServletResponse r = withCookie("/admin/posts", new Cookie(COOKIE, v));
            assertThat(r.getRedirectedUrl()).endsWith("/admin/login");
            assertThat(cleared(r)).isTrue();
        }
    }

    @Test
    void bogusBearerNeverFallsBackToTheCookie() throws Exception {
        Cookie c = rememberedDevice();
        int code = mvc.perform(get("/api/cameras/cameras").cookie(c).header("Authorization", "Bearer not-a-token"))
                .andReturn().getResponse().getStatus();
        assertThat(code).isEqualTo(401);
    }

    // ------------------------------------------------------------------ revocation

    @Test
    void logoutForgetsThisDevice() throws Exception {
        MvcResult r = login(true, null);
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        Cookie c = new Cookie(COOKIE, r.getResponse().getCookie(COOKIE).getValue());
        Cookie other = rememberedDevice();

        MockHttpServletResponse out = mvc.perform(post("/admin/logout").session(s).cookie(c).with(csrf()))
                .andReturn().getResponse();
        assertThat(out.getStatus()).isEqualTo(302);
        assertThat(cleared(out)).isTrue();
        assertThat(signedIn(c)).isFalse();
        assertThat(signedIn(other)).isTrue(); // only this device
        assertThat(remembered.count()).isEqualTo(1);
    }

    @Test
    void revokeAllForgetsRememberedDevices() throws Exception {
        MvcResult r = login(true, null);
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        Cookie c = new Cookie(COOKIE, r.getResponse().getCookie(COOKIE).getValue());
        Cookie other = rememberedDevice();

        String body = mvc.perform(post("/api/cameras/auth/revoke-all").session(s).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"devices\":2").contains("\"revoked\":0");
        assertThat(remembered.count()).isZero();
        assertThat(signedIn(c)).isFalse();
        assertThat(signedIn(other)).isFalse();
    }

    // ------------------------------------------------------------------ never the gallery

    @Test
    void galleryIgnoresRememberCookie() throws Exception {
        Cookie c = rememberedDevice();
        MockHttpServletResponse r = withCookie("/mae", c);
        assertThat(r.getStatus()).isEqualTo(302);
        assertThat(r.getRedirectedUrl()).contains("/mae/login");
        assertThat(r.getCookie(COOKIE)).isNull(); // not even looked at
        assertThat(withCookie("/mae/api/items", c).getStatus()).isNotEqualTo(200);
    }

    @Test
    void familySessionPlusCookieNeverReachesAdminAsFamily() throws Exception {
        MvcResult fam = mvc.perform(post("/mae/login").param("password", "testfamily").with(csrf())).andReturn();
        MockHttpSession s = (MockHttpSession) fam.getRequest().getSession(false);
        assertThat(mvc.perform(get("/mae").session(s)).andReturn().getResponse().getStatus()).isEqualTo(200);
        // family session alone: no admin
        assertThat(mvc.perform(get("/admin/posts").session(s)).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(mvc.perform(get("/api/cameras/cameras").session(s)).andReturn().getResponse().getStatus())
                .isIn(401, 403);
    }
}
