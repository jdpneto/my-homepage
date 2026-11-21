package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AdminCamerasPageIT {

    @Autowired WebApplicationContext ctx;
    @Autowired CameraProperties props;
    @Autowired ClipIndexer indexer;
    @Autowired CameraClipRepository repo;
    @Autowired AlertMute mute;
    @Autowired CameraSettingRepository settings;
    MockMvc mvc;
    Path root;
    final RequestPostProcessor admin = user("admin").roles("ADMIN");

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        root = props.getRootDir();
        FileSystemUtils.deleteRecursively(root);
        repo.deleteAll();
        settings.deleteAll();
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
    }

    @AfterEach
    void tearDown() {
        FileSystemUtils.deleteRecursively(root.toFile());
        repo.deleteAll();
        settings.deleteAll();
    }

    void clip(String camera, String date, String time, String start, String trigger) throws Exception {
        Path mp4 = root.resolve(camera + "/" + date + "/" + time + ".mp4");
        Files.createDirectories(mp4.getParent());
        Files.write(mp4, new byte[100]);
        Files.writeString(root.resolve(camera + "/" + date + "/" + time + ".json"), """
                {"camera_name":"%s","camera_id":"id-%s","channel":0,"start":"%s","end":"%s",
                 "duration_seconds":10,"triggers":["%s"]}""".formatted(camera, camera, start,
                OffsetDateTime.parse(start).plusSeconds(10), trigger));
    }

    void fourClips() throws Exception {
        clip("Garage", "2026-11-18", "21-45-49", "2026-11-18T21:45:49+01:00", "person");
        clip("Garage", "2026-11-18", "21-49-41", "2026-11-18T21:49:41+01:00", "motion");
        clip("Living Room", "2026-11-18", "20-00-00", "2026-11-18T20:00:00+01:00", "person");
        clip("Backyard", "2026-11-16", "19-22-58", "2026-11-16T19:22:58+01:00", "animal");
        indexer.reconcile();
    }

    String page(String url) throws Exception {
        return mvc.perform(get(URI.create(url)).with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    static int count(String html, String needle) {
        int n = 0, i = 0;
        while ((i = html.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    void pageRendersGroupsNewestFirst() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(count(html, "data-id=\"")).isEqualTo(4);
        assertThat(count(html, "<h3 class=\"day\"")).isEqualTo(2);
        assertThat(html.indexOf("21:49:41")).isLessThan(html.indexOf("21:45:49"));
        assertThat(html.indexOf("21:45:49")).isLessThan(html.indexOf("19:22:58"));
    }

    @Test
    void groupLabelsTodayYesterdayDate() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-18T22:00:00+01:00");
        List<ClipView> clips = List.of(
                view(1, "2026-11-18T21:00:00+01:00"), view(2, "2026-11-17T09:00:00+01:00"),
                view(3, "2026-11-16T19:22:58+01:00"));
        List<ClipDayGroup> groups = AdminCamerasController.group(clips, now);
        assertThat(groups).extracting(ClipDayGroup::label).containsExactly("Today", "Yesterday", "Mon 16 Nov");
    }

    static ClipView view(long id, String start) {
        OffsetDateTime s = OffsetDateTime.parse(start);
        return new ClipView(id, "Garage", "g", s, s.plusSeconds(10), 10, List.of("person"), 100, false);
    }

    @Test
    void filterLinksEncodeNamesAndFilter() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(html).contains("camera=Living%20Room");
        String filtered = page("/admin/cameras?camera=Living%20Room");
        assertThat(count(filtered, "data-id=\"")).isEqualTo(1);
        assertThat(count(page("/admin/cameras?trigger=person"), "data-id=\"")).isEqualTo(2);
    }

    @Test
    void cameraCardsLinkToPerCameraView() throws Exception {
        fourClips();
        String html = page("/admin/cameras?camera=Garage");
        assertThat(count(html, "class=\"camera-card\"")).isEqualTo(4); // "All cameras" + 3
        long garageLatest = repo.findByStem("Garage/2026-11-18/21-49-41").orElseThrow().getId();
        assertThat(html).contains("/api/cameras/clips/" + garageLatest + "/thumb");
        assertThat(html).contains("aria-current=\"true\"");
        assertThat(html).contains("2 clips");
    }

    @Test
    void storagePanelShowsFormattedSizes() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(html).contains("Kept").contains("Last 30 days").contains("Free").contains("Uploader");
        assertThat(html).containsPattern("\\d+(\\.\\d)? (B|KB|MB|GB)");
    }

    @Test
    void alertsSwitchShowsState() throws Exception {
        assertThat(page("/admin/cameras")).contains("Alerts on");
        mute.set(true, null);
        assertThat(page("/admin/cameras")).contains("Alerts off");
        mute.set(true, java.time.Instant.now().plusSeconds(3600));
        assertThat(page("/admin/cameras")).contains("Alerts muted until");
    }

    @Test
    void emptyStateMessage() throws Exception {
        String html = page("/admin/cameras");
        assertThat(html).contains("No clips");
        assertThat(count(html, "data-id=\"")).isZero();
    }

    @Test
    void requiresAdminAndIsNotIndexed() throws Exception {
        mvc.perform(get("/admin/cameras")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
        mvc.perform(get("/admin/cameras").with(admin))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        assertThat(page("/admin/cameras")).contains("<meta name=\"csrf\"").contains("noindex,nofollow");
    }

    @Test
    void navHasCamerasLink() throws Exception {
        assertThat(page("/admin/posts")).contains("href=\"/admin/cameras\"");
    }

    @Test
    void markupContractForTheScript() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(page("/admin/cameras?camera=Living%20Room")).contains("data-query=\"camera=Living%20Room\"");
        assertThat(html).contains("id=\"clips\"").contains("id=\"clip-row\"")
                .contains("id=\"player\"").contains("id=\"storage\"").contains("id=\"revoke-all\"")
                .contains("id=\"test-alert\"").contains("id=\"alerts-toggle\"").contains("id=\"alerts-mute-8h\"")
                .contains("data-kept=").contains("data-start=").contains("/js/cameras.js").contains("/css/cameras.css");
    }

    @Test
    void staticAssetsServed() throws Exception {
        String js = mvc.perform(get("/js/cameras.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("X-XSRF-TOKEN").contains("IntersectionObserver").contains("/api/cameras/");
        assertThat(js).doesNotContain("innerHTML ="); // names and triggers go in as text, never HTML
        mvc.perform(get("/css/cameras.css")).andExpect(status().isOk());
    }

    @Test
    void filterLinksOmitEmptyCameraParameter() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(html).doesNotContain("?camera=&amp;").doesNotContain("?camera=\"").doesNotContain("?camera=&");
        assertThat(html).contains("href=\"/admin/cameras?trigger=person\"");
    }

    @Test
    void emptyFilterResultSaysSoAndOffersToClear() throws Exception {
        fourClips();
        String html = page("/admin/cameras?camera=Backyard&trigger=person");
        assertThat(html).contains("No clips match this filter").contains("href=\"/admin/cameras\"");
        assertThat(page("/admin/cameras?kept=true")).contains("No clips match this filter");
    }

    @Test
    void alertsAndDeviceControlsAreCollapsible() throws Exception {
        String html = page("/admin/cameras");
        assertThat(html).contains("<details").contains("<summary");
        assertThat(html.indexOf("<details")).isLessThan(html.indexOf("id=\"alerts-toggle\""));
    }

    @Test
    void rowsUseOrdinaryDoubleQuotedDataCamera() throws Exception {
        fourClips();
        assertThat(page("/admin/cameras")).contains("data-camera=\"Living Room\"");
    }

    @Test
    void revokeAllAsksFirst() throws Exception {
        String js = mvc.perform(get("/js/cameras.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("Yes, sign out all");
    }

    @Test
    void manifestLinkedAndPublic() throws Exception {
        // Manifest: public (no login), opens the camera page full-screen.
        var manifest = mvc.perform(get("/cameras.webmanifest")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(manifest.getContentType()).contains("manifest+json");
        var m = tools.jackson.databind.json.JsonMapper.builder().build().readTree(manifest.getContentAsString());
        assertThat(m.get("name").asString()).isEqualTo("Cameras");
        assertThat(m.get("short_name").asString()).isEqualTo("Cameras");
        assertThat(m.get("start_url").asString()).isEqualTo("/admin/cameras");
        assertThat(m.get("scope").asString()).isEqualTo("/");
        assertThat(m.get("display").asString()).isEqualTo("standalone");
        assertThat(m.get("background_color").asString()).isEqualTo("#0a0a0a");
        assertThat(m.get("theme_color").asString()).isEqualTo("#0a0a0a");
        List<String> iconSrcs = new java.util.ArrayList<>();
        m.get("icons").forEach(i -> iconSrcs.add(i.get("src").asString() + " " + i.get("sizes").asString()));
        assertThat(iconSrcs).contains("/icons/cameras-192.png 192x192", "/icons/cameras-512.png 512x512");

        // Icons: public PNGs of the right pixel size.
        for (var icon : java.util.Map.of("/icons/cameras-192.png", 192, "/icons/cameras-512.png", 512,
                "/icons/apple-touch-icon.png", 180).entrySet()) {
            var r = mvc.perform(get(icon.getKey())).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(r.getContentType()).as(icon.getKey()).isEqualTo("image/png");
            var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.getContentAsByteArray()));
            assertThat(img).as(icon.getKey()).isNotNull();
            assertThat(img.getWidth()).as(icon.getKey()).isEqualTo(icon.getValue());
            assertThat(img.getHeight()).as(icon.getKey()).isEqualTo(icon.getValue());
        }

        // Linked from the camera page and the login page (the home-screen shortcut may land on either).
        String login = mvc.perform(get("/admin/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String html : List.of(page("/admin/cameras"), login)) {
            assertThat(html).contains("rel=\"manifest\" href=\"/cameras.webmanifest\"")
                    .contains("rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon.png\"")
                    .contains("name=\"apple-mobile-web-app-capable\" content=\"yes\"")
                    .contains("name=\"mobile-web-app-capable\" content=\"yes\"")
                    .contains("name=\"theme-color\" content=\"#0a0a0a\"");
        }
    }

    @Test
    void revokeButtonSignsOutAllDevicesIncludingRemembered() throws Exception {
        assertThat(page("/admin/cameras")).contains(">Sign out all devices</button>")
                .doesNotContain("Sign out all app devices");
        String js = mvc.perform(get("/js/cameras.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("body.devices").contains("remembered");
    }

    @Test
    void navLogoutIsAPostFormThatLogsOut() throws Exception {
        String html = page("/admin/cameras");
        assertThat(html).contains("<form").contains("action=\"/admin/logout\"").contains("method=\"post\"");
        org.springframework.mock.web.MockHttpSession s = (org.springframework.mock.web.MockHttpSession)
                mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                        .formLogin("/admin/login").user("admin").password("admin")).andReturn().getRequest().getSession(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/logout").session(s)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/cameras").session(s)).andExpect(status().is3xxRedirection());
    }

    @Test
    void plusInCameraNameIsEncodedInDataQuery() throws Exception {
        clip("Cam+1", "2026-11-18", "21-45-49", "2026-11-18T21:45:49+01:00", "person");
        indexer.reconcile();
        assertThat(page("/admin/cameras?camera=Cam%2B1")).contains("data-query=\"camera=Cam%2B1\"");
    }

    @Test
    void muteUntilIsRenderedAsIsoForTheBrowserAndInTheNewestClipsOffsetEvenWhenFiltered() throws Exception {
        fourClips();
        mute.set(true, java.time.Instant.parse("2099-01-01T20:00:00Z"));
        String kept = page("/admin/cameras?kept=true"); // empty filter: must not fall back to UTC
        assertThat(kept).contains("data-until=\"2099-01-01T20:00:00Z\"");
        assertThat(kept).contains("Alerts muted until 21:00");
    }

    @Test
    void rowsCarrySize() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        long size = repo.findAll().get(0).getSizeBytes();
        assertThat(size).isPositive();
        assertThat(html).containsPattern("data-size=\"\\d+\"").contains("data-size=\"" + size + "\"");
    }

    @Test
    void selectModeAndDayDeleteMarkupPresent() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        assertThat(html).contains("id=\"select-toggle\"").contains("class=\"day-delete\"").contains("id=\"select-bar\"")
                .contains("id=\"select-count\"").contains("id=\"select-delete\"").contains("id=\"select-cancel\"");
    }

    @Test
    void bulkDeleteScriptUsesTheEndpointAndConfirms() throws Exception {
        String js = mvc.perform(get("/js/cameras.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("/clips/delete").contains("Yes, delete").contains("kept stay");
    }

    /** Each row's star is a button of its own, beside (never inside) the row's .clip-open button. */
    @Test
    void starIsItsOwnButton() throws Exception {
        fourClips();
        long keptId = repo.findByStem("Garage/2026-11-18/21-49-41").orElseThrow().getId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/cameras/clips/" + keptId + "/keep").with(admin)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
        String html = page("/admin/cameras");

        // Every <li class="clip"> (rendered rows and the script's <template> row).
        java.util.regex.Matcher rows = java.util.regex.Pattern
                .compile("<li class=\"clip\"[^>]*>(.*?)</li>", java.util.regex.Pattern.DOTALL).matcher(html);
        int n = 0;
        while (rows.find()) {
            n++;
            String row = rows.group(0);
            String body = rows.group(1);
            java.util.regex.Matcher star = java.util.regex.Pattern
                    .compile("<button type=\"button\" class=\"clip-kept\"[^>]*>").matcher(body);
            assertThat(star.find()).as("star button in %s", row).isTrue();
            assertThat(star.group()).contains("aria-pressed=\"").contains("aria-label=\"Keep clip\"");
            // After the .clip-open button has closed: a sibling, not a child.
            int openEnd = body.indexOf("</button>", body.indexOf("class=\"clip-open\""));
            assertThat(star.start()).as("star after .clip-open in %s", row).isGreaterThan(openEnd);
            boolean renderedKept = row.contains("data-kept=\"true\"");
            if (row.contains("data-id=\"")) {
                assertThat(star.group()).contains("aria-pressed=\"" + renderedKept + "\"");
            }
        }
        assertThat(n).isEqualTo(5); // 4 rows + the template
        assertThat(html).containsPattern("data-id=\"" + keptId + "\"[^>]*data-kept=\"true\"");
        assertThat(html).containsPattern("class=\"clip-kept\"[^>]*aria-pressed=\"true\"[^>]*>★</button>");

        // No button anywhere inside another button.
        java.util.regex.Matcher tags = java.util.regex.Pattern.compile("<button\\b|</button>").matcher(html);
        int depth = 0;
        while (tags.find()) {
            if (tags.group().equals("</button>")) {
                depth--;
            } else {
                assertThat(depth).as("button nested at offset %d", tags.start()).isZero();
                depth++;
            }
        }
        assertThat(depth).isZero();
        // The star is no longer an image inside the row's button.
        assertThat(html).doesNotContain("class=\"clip-kept\" role=\"img\"");
    }

    @Test
    void selectBarHasKeepAndDownload() throws Exception {
        fourClips();
        String html = page("/admin/cameras");
        int bar = html.indexOf("id=\"select-bar\"");
        assertThat(bar).isPositive();
        int barEnd = html.indexOf("id=\"clip-row\"", bar);
        String barHtml = html.substring(bar, barEnd);
        assertThat(barHtml).contains("<button type=\"button\" id=\"select-keep\"")
                .contains("<button type=\"button\" id=\"select-download\"")
                .contains("id=\"select-delete\"").contains("id=\"select-cancel\"");
    }

    @Test
    void scriptHasBulkKeepZipAndShare() throws Exception {
        String js = mvc.perform(get("/js/cameras.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("/clips/keep").contains("/clips/zip").contains("navigator.share")
                .contains("navigator.canShare").contains("Save ").contains("Preparing ")
                .contains("Select up to 30 clips to save at once").contains("video/mp4");
    }

    static String healthLine(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<div class=\"cam-health\"[^>]*>([^<]*)</div>").matcher(html);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** The mode is shown (and switched) by #mode-switch; the health line keeps uploader and hub only. */
    @Test
    void healthLineLeavesTheModeToTheSwitch() throws Exception {
        String now = OffsetDateTime.now().toString();
        Files.writeString(root.resolve("heartbeat.json"),
                "{\"time\":\"" + now + "\",\"hub_ok\":true,\"mode\":\"home\"}");
        String html = page("/admin/cameras");
        assertThat(healthLine(html)).endsWith("Hub OK").doesNotContain("mode:");
        assertThat(pressed(html)).containsExactly("home");

        Files.writeString(root.resolve("heartbeat.json"), "{\"time\":\"" + now + "\",\"hub_ok\":true}");
        html = page("/admin/cameras");
        assertThat(healthLine(html)).endsWith("Hub OK").doesNotContain("mode:");
        assertThat(pressed(html)).isEmpty();

        Files.writeString(root.resolve("heartbeat.json"),
                "{\"time\":\"" + now + "\",\"hub_ok\":true,\"mode\":null}");
        assertThat(pressed(page("/admin/cameras"))).isEmpty();

        Files.writeString(root.resolve("heartbeat.json"),
                "{\"time\":\"" + now + "\",\"hub_ok\":true,\"mode\":\" \"}");
        assertThat(pressed(page("/admin/cameras"))).isEmpty();
    }

    /** data-mode of every mode button rendered with aria-pressed="true". */
    static List<String> pressed(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<button[^>]*data-mode=\"(\\w+)\"[^>]*>").matcher(html);
        List<String> modes = new java.util.ArrayList<>();
        while (m.find()) {
            if (m.group(0).contains("aria-pressed=\"true\"")) modes.add(m.group(1));
        }
        return modes;
    }

    @Test
    void modeSwitchMarkup() throws Exception {
        Files.writeString(root.resolve("heartbeat.json"), "{\"time\":\"" + OffsetDateTime.now()
                + "\",\"hub_ok\":true,\"mode\":\"away\"}");
        String html = page("/admin/cameras");
        assertThat(html).contains("id=\"mode-switch\"");
        assertThat(html).contains("data-mode=\"disarm\"").contains("data-mode=\"home\"").contains("data-mode=\"away\"")
                .doesNotContain("data-mode=\"off\"");
        assertThat(pressed(html)).containsExactly("away");
        assertThat(html).contains("Mode:");
        // Inside the storage panel, right after the health line.
        assertThat(html.indexOf("id=\"mode-switch\"")).isGreaterThan(html.indexOf("class=\"cam-health\""))
                .isLessThan(html.indexOf("<details"));

        String js = mvc.perform(get("/js/cameras.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("/mode").contains("Switching to").contains("Expired")
                .contains("Not applied: ").contains("the Mac mini didn't pick it up").contains("Yes, disarm");
        // Polls past expires_at + the server's grace (6 min), so "Expired" is seen rather than a time-out.
        assertThat(js).contains("MODE_POLL_MAX_MS = 7 * 60 * 1000");
    }

    @Test
    void pendingRequestIsRenderedSoThePageKeepsPolling() throws Exception {
        assertThat(page("/admin/cameras")).doesNotContain("data-pending-id=");
        Files.writeString(root.resolve("mode-request.json"), "{\"id\":\"abc-123\",\"mode\":\"away\",\"requested_at\":\""
                + OffsetDateTime.now() + "\",\"expires_at\":\"" + OffsetDateTime.now().plusMinutes(5) + "\"}");
        String html = page("/admin/cameras");
        assertThat(html).contains("data-pending-id=\"abc-123\"").contains("data-pending-mode=\"away\"");
    }
}
