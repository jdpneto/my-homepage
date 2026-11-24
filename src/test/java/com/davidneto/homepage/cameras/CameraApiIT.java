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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CameraApiIT {

    @Autowired WebApplicationContext ctx;
    @Autowired CameraProperties props;
    @Autowired ClipIndexer indexer;
    @Autowired CameraClipRepository repo;
    @Autowired CameraSettingRepository settings;
    MockMvc mvc;
    Path root;
    final JsonMapper json = JsonMapper.builder().build();
    final RequestPostProcessor admin = user("admin").roles("ADMIN");
    static final byte[] VIDEO = new byte[1000];

    static {
        for (int i = 0; i < VIDEO.length; i++) VIDEO[i] = (byte) i;
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        root = props.getRootDir();
        FileSystemUtils.deleteRecursively(root);
        FileSystemUtils.deleteRecursively(props.getThumbsDir());
        repo.deleteAll();
        settings.deleteAll();
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
        clip("Garage", "2026-11-18", "21-45-49", "2026-11-18T21:45:49+01:00", "person");
        clip("Garage", "2026-11-18", "21-49-41", "2026-11-18T21:49:41+01:00", "motion");
        clip("Living Room", "2026-11-18", "20-00-00", "2026-11-18T20:00:00+01:00", "person");
        indexer.reconcile();
    }

    @AfterEach
    void tearDown() {
        FileSystemUtils.deleteRecursively(root.toFile());
        repo.deleteAll();
    }

    void clip(String camera, String date, String time, String start, String trigger) throws Exception {
        Path mp4 = root.resolve(camera + "/" + date + "/" + time + ".mp4");
        Files.createDirectories(mp4.getParent());
        Files.write(mp4, VIDEO);
        Files.writeString(root.resolve(camera + "/" + date + "/" + time + ".json"), """
                {"camera_name":"%s","camera_id":"id","channel":0,"start":"%s","end":"%s",
                 "duration_seconds":10,"triggers":["%s"]}""".formatted(camera, start,
                java.time.OffsetDateTime.parse(start).plusSeconds(10), trigger));
    }

    JsonNode getJson(String url) throws Exception {
        // A URI (not a template) so already-encoded query values (%20, %2B) are sent as-is.
        return json.readTree(mvc.perform(get(java.net.URI.create(url)).with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    long newestId() throws Exception {
        return getJson("/api/cameras/clips?limit=1").get("clips").get(0).get("id").asLong();
    }

    @Test
    void listReturnsNewestFirstAndCursor() throws Exception {
        JsonNode page1 = getJson("/api/cameras/clips?limit=2");
        assertThat(page1.get("clips")).hasSize(2);
        assertThat(page1.get("clips").get(0).get("start").asString()).isEqualTo("2026-11-18T21:49:41+01:00");
        assertThat(page1.get("clips").get(0).get("camera").asString()).isEqualTo("Garage");
        assertThat(page1.get("clips").get(0).get("triggers").get(0).asString()).isEqualTo("motion");
        String next = page1.get("nextBefore").asString();
        JsonNode page2 = getJson("/api/cameras/clips?limit=2&before=" + next);
        assertThat(page2.get("clips")).hasSize(1);
        assertThat(page2.get("nextBefore").isNull()).isTrue();
    }

    @Test
    void filterByCameraWithSpaceAndTriggerAndRange() throws Exception {
        JsonNode r = getJson("/api/cameras/clips?camera=Living%20Room");
        assertThat(r.get("clips")).hasSize(1);
        assertThat(r.get("clips").get(0).get("camera").asString()).isEqualTo("Living Room");
        assertThat(getJson("/api/cameras/clips?trigger=person").get("clips")).hasSize(2);
        assertThat(getJson("/api/cameras/clips?from=2026-11-18T21:00:00%2B01:00").get("clips")).hasSize(2);
        assertThat(getJson("/api/cameras/clips?to=2026-11-18T21:00:00%2B01:00").get("clips")).hasSize(1);
    }

    @Test
    void videoFullAndRanges() throws Exception {
        long id = newestId();
        var full = mvc.perform(get("/api/cameras/clips/" + id + "/video").with(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn().getResponse();
        assertThat(full.getContentAsByteArray()).hasSize(1000);
        assertThat(full.getHeader("Cache-Control")).contains("private");

        var probe = mvc.perform(get("/api/cameras/clips/" + id + "/video").with(admin).header("Range", "bytes=0-1"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-1/1000"))
                .andReturn().getResponse();
        assertThat(probe.getContentAsByteArray()).containsExactly(0, 1);

        var tail = mvc.perform(get("/api/cameras/clips/" + id + "/video").with(admin).header("Range", "bytes=100-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 100-999/1000"))
                .andReturn().getResponse();
        assertThat(tail.getContentAsByteArray()).hasSize(900);
    }

    @Test
    void downloadHasAttachmentFilename() throws Exception {
        long id = getJson("/api/cameras/clips?camera=Living%20Room").get("clips").get(0).get("id").asLong();
        String cd = mvc.perform(get("/api/cameras/clips/" + id + "/download").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Content-Disposition");
        assertThat(cd).startsWith("attachment").contains("Living");
        assertThat(cd).contains("2026-11-18_20-00-00.mp4");
    }

    @Test
    void thumbOr404() throws Exception {
        long id = newestId();
        mvc.perform(get("/api/cameras/clips/" + id + "/thumb").with(admin)).andExpect(status().isNotFound());
        assumeTrue(ClipThumbnailerTest.onPath("ffmpeg"), "ffmpeg not on PATH");
        Path mp4 = root.resolve("Garage/2026-11-18/21-49-41.mp4");
        ClipThumbnailerTest.run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc=size=640x360:rate=15:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-f", "mp4", mp4.toString());
        mvc.perform(get("/api/cameras/clips/" + id + "/thumb").with(admin))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    void keepUnkeepRoundTrip() throws Exception {
        long id = newestId();
        mvc.perform(put("/api/cameras/clips/" + id + "/keep").with(admin).with(csrf())).andExpect(status().isOk());
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).exists();
        assertThat(getJson("/api/cameras/clips/" + id).get("kept").asBoolean()).isTrue();
        mvc.perform(delete("/api/cameras/clips/" + id + "/keep").with(admin).with(csrf())).andExpect(status().isOk());
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).doesNotExist();
    }

    @Test
    void deleteKept409ThenUnkeepThenDelete204() throws Exception {
        long id = newestId();
        mvc.perform(put("/api/cameras/clips/" + id + "/keep").with(admin).with(csrf()));
        String body = mvc.perform(delete("/api/cameras/clips/" + id).with(admin).with(csrf()))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("unkeep it first");
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).exists();
        mvc.perform(delete("/api/cameras/clips/" + id + "/keep").with(admin).with(csrf()));
        mvc.perform(delete("/api/cameras/clips/" + id).with(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).doesNotExist();
    }

    @Test
    void unknownId404() throws Exception {
        for (String url : List.of("/api/cameras/clips/999999", "/api/cameras/clips/999999/video",
                "/api/cameras/clips/999999/thumb", "/api/cameras/clips/999999/download")) {
            mvc.perform(get(url).with(admin)).andExpect(status().isNotFound());
        }
    }

    @Test
    void statusShapeAndCameras() throws Exception {
        JsonNode s = getJson("/api/cameras/status");
        assertThat(s.get("volumeOk").asBoolean()).isTrue();
        assertThat(s.has("quotaBytes")).isTrue();
        assertThat(s.has("keptBytes")).isTrue();
        assertThat(s.get("warnings").isArray()).isTrue();
        assertThat(s.get("newestByCamera").get("Garage").asString()).isEqualTo("2026-11-18T21:49:41+01:00");
        JsonNode cams = getJson("/api/cameras/cameras");
        assertThat(cams.get(0).get("name").asString()).isEqualTo("Garage");
        assertThat(cams.get(0).get("cameraId").asString()).isEqualTo("id");
        assertThat(cams.get(0).get("clipCount").asLong()).isEqualTo(2);
        assertThat(cams.get(0).get("latestStart").asString()).isEqualTo("2026-11-18T21:49:41+01:00");
        assertThat(cams.get(0).get("latestClipId").asLong()).isEqualTo(newestId());
        assertThat(cams.get(1).get("name").asString()).isEqualTo("Living Room");
    }

    @Test
    void sessionWritesNeedCsrf() throws Exception {
        mvc.perform(put("/api/cameras/clips/" + newestId() + "/keep").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void testAlertEndpointReportsNotSentWithoutTopic() throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/cameras/alerts/test").with(admin).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("sent").asBoolean()).isFalse();
        // Unauthenticated POST: refused (CSRF check answers 403 before authentication's 401).
        int code = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/cameras/alerts/test")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(401, 403);
    }

    @Test
    void deletingTheCursorClipDoesNotRestartTheList() throws Exception {
        JsonNode page1 = getJson("/api/cameras/clips?limit=2");
        long lastId = page1.get("clips").get(1).get("id").asLong();
        String cursor = page1.get("nextBefore").asString();
        mvc.perform(delete("/api/cameras/clips/" + lastId).with(admin).with(csrf())).andExpect(status().isNoContent());
        JsonNode page2 = getJson("/api/cameras/clips?limit=2&before=" + cursor);
        assertThat(page2.get("clips")).hasSize(1);
        assertThat(page2.get("clips").get(0).get("camera").asString()).isEqualTo("Living Room");
    }

    @Test
    void malformedCursorIs400() throws Exception {
        mvc.perform(get("/api/cameras/clips?before=abc").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void muteEndpointRoundTrip() throws Exception {
        JsonNode initial = getJson("/api/cameras/alerts/mute");
        assertThat(initial.get("muted").asBoolean()).isFalse();
        mvc.perform(put("/api/cameras/alerts/mute").with(admin).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"muted\":true,\"until\":\"" + java.time.OffsetDateTime.now().plusHours(8).withNano(0) + "\"}"))
                .andExpect(status().isOk());
        JsonNode after = getJson("/api/cameras/alerts/mute");
        assertThat(after.get("muted").asBoolean()).isTrue();
        assertThat(after.get("until").asString()).isNotBlank();
        mvc.perform(put("/api/cameras/alerts/mute").with(admin)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"muted\":false}"))
                .andExpect(status().isForbidden()); // no CSRF
        mvc.perform(put("/api/cameras/alerts/mute").with(admin).with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"muted\":false}"));
    }

    @Test
    void muteUntilMustBeWithinAYear() throws Exception {
        for (String until : new String[]{"+999999999-12-31T23:59:59Z", "2000-01-01T00:00:00Z"}) {
            mvc.perform(put("/api/cameras/alerts/mute").with(admin).with(csrf())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"muted\":true,\"until\":\"" + until + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/admin/cameras").with(admin)).andExpect(status().isOk());
    }

    org.springframework.test.web.servlet.ResultActions bulk(String json, RequestPostProcessor... with) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/cameras/clips/delete")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json);
        for (RequestPostProcessor w : with) req = req.with(w);
        return mvc.perform(req);
    }

    @Test
    void bulkDeleteReturnsOutcomes() throws Exception {
        JsonNode clips = getJson("/api/cameras/clips?limit=2").get("clips");
        long a = clips.get(1).get("id").asLong(), kept = clips.get(0).get("id").asLong();
        mvc.perform(put("/api/cameras/clips/" + kept + "/keep").with(admin).with(csrf())).andExpect(status().isOk());
        String body = bulk("{\"ids\":[" + a + "," + kept + ",424242]}", admin, csrf())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertThat(r.get("deleted").toString()).isEqualTo("[" + a + "]");
        assertThat(r.get("skippedKept").toString()).isEqualTo("[" + kept + "]");
        assertThat(r.get("notFound").toString()).isEqualTo("[424242]");
        assertThat(r.get("failed").toString()).isEqualTo("[]");
        assertThat(r.get("freedBytes").asLong()).isPositive();
        mvc.perform(get("/api/cameras/clips/" + a).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/cameras/clips/" + kept).with(admin)).andExpect(status().isOk());
    }

    @Test
    void bulkDeleteValidates() throws Exception {
        String tooMany = "{\"ids\":[" + String.join(",", java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(Long::toString).toList()) + "]}";
        for (String json : List.of("{\"ids\":[]}", tooMany, "{}", "{\"ids\":[\"x\"]}")) {
            bulk(json, admin, csrf()).andExpect(status().isBadRequest());
        }
        assertThat(getJson("/api/cameras/clips?limit=50").get("clips").size()).isPositive();
    }

    @Test
    void bulkDeleteNeedsCsrfAndAuth() throws Exception {
        long id = newestId();
        bulk("{\"ids\":[" + id + "]}", admin).andExpect(status().isForbidden());
        bulk("{\"ids\":[" + id + "]}", csrf()).andExpect(status().isUnauthorized()); // CSRF is checked first
        mvc.perform(get("/api/cameras/clips/" + id).with(admin)).andExpect(status().isOk());
    }

    @Test
    void bulkDeleteIs503WhileTheVolumeIsMissing() throws Exception {
        long id = newestId();
        Files.delete(root.resolve("volume-marker.txt"));
        bulk("{\"ids\":[" + id + "]}", admin, csrf()).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/cameras/clips/" + id).with(admin)).andExpect(status().isOk());
    }

    org.springframework.test.web.servlet.ResultActions bulkKeep(String json, RequestPostProcessor... with) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/cameras/clips/keep")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json);
        for (RequestPostProcessor w : with) req = req.with(w);
        return mvc.perform(req);
    }

    @Test
    void bulkKeepReturnsOutcomes() throws Exception {
        JsonNode clips = getJson("/api/cameras/clips?limit=3").get("clips");
        long a = clips.get(0).get("id").asLong(), already = clips.get(1).get("id").asLong();
        long untouched = clips.get(2).get("id").asLong();
        mvc.perform(put("/api/cameras/clips/" + already + "/keep").with(admin).with(csrf())).andExpect(status().isOk());
        String body = bulkKeep("{\"ids\":[" + a + "," + already + ",424242," + a + "]}", admin, csrf())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("kept").toString()).isEqualTo("[" + a + "," + already + "]");
        assertThat(r.get("notFound").toString()).isEqualTo("[424242]");
        assertThat(r.get("failed").toString()).isEqualTo("[]");
        assertThat(r.get("keptBytes").asLong()).isEqualTo(2L * VIDEO.length);
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).exists();
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.keep")).exists();
        assertThat(getJson("/api/cameras/clips/" + a).get("kept").asBoolean()).isTrue();
        assertThat(getJson("/api/cameras/clips/" + untouched).get("kept").asBoolean()).isFalse();
    }

    @Test
    void bulkKeepValidates() throws Exception {
        String tooMany = "{\"ids\":[" + String.join(",", java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(Long::toString).toList()) + "]}";
        for (String json : List.of("{\"ids\":[]}", tooMany, "{}", "{\"ids\":[\"x\"]}")) {
            bulkKeep(json, admin, csrf()).andExpect(status().isBadRequest());
        }
        assertThat(getJson("/api/cameras/clips?kept=true").get("clips").size()).isZero();
    }

    @Test
    void bulkKeepNeedsCsrfAndAuth() throws Exception {
        long id = newestId();
        bulkKeep("{\"ids\":[" + id + "]}", admin).andExpect(status().isForbidden());
        bulkKeep("{\"ids\":[" + id + "]}", csrf()).andExpect(status().isUnauthorized());
        assertThat(getJson("/api/cameras/clips/" + id).get("kept").asBoolean()).isFalse();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).doesNotExist();
    }

    @Test
    void bulkKeepIs503WhileTheVolumeIsMissing() throws Exception {
        long id = newestId();
        Files.delete(root.resolve("volume-marker.txt"));
        bulkKeep("{\"ids\":[" + id + "]}", admin, csrf()).andExpect(status().isServiceUnavailable());
        assertThat(getJson("/api/cameras/clips/" + id).get("kept").asBoolean()).isFalse();
    }

    /** Entry name to bytes, in zip order. */
    static java.util.LinkedHashMap<String, byte[]> unzip(byte[] zip) throws Exception {
        var out = new java.util.LinkedHashMap<String, byte[]>();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }

    @Test
    void zipContainsTheSelectedClips() throws Exception {
        long living = getJson("/api/cameras/clips?camera=Living%20Room").get("clips").get(0).get("id").asLong();
        long garage = newestId();
        Files.write(root.resolve("Living Room/2026-11-18/20-00-00.mp4"), new byte[]{9, 8, 7});
        var res = mvc.perform(get("/api/cameras/clips/zip").param("ids", garage + "," + living + "," + garage)
                        .with(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse();
        assertThat(res.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"cameras-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip\"");
        var entries = unzip(res.getContentAsByteArray());
        assertThat(entries.keySet()).containsExactly("Garage/2026-11-18/21-49-41.mp4",
                "Living Room/2026-11-18/20-00-00.mp4");
        assertThat(entries.get("Garage/2026-11-18/21-49-41.mp4")).isEqualTo(VIDEO);
        assertThat(entries.get("Living Room/2026-11-18/20-00-00.mp4")).isEqualTo(new byte[]{9, 8, 7});
    }

    @Test
    void zipSkipsUnknownAnd404WhenNone() throws Exception {
        long garage = newestId();
        long living = getJson("/api/cameras/clips?camera=Living%20Room").get("clips").get(0).get("id").asLong();
        Files.delete(root.resolve("Living Room/2026-11-18/20-00-00.mp4")); // row still there, file gone
        byte[] zip = mvc.perform(get("/api/cameras/clips/zip").param("ids", "424242," + living + "," + garage)
                        .with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(unzip(zip).keySet()).containsExactly("Garage/2026-11-18/21-49-41.mp4");
        mvc.perform(get("/api/cameras/clips/zip").param("ids", "424242," + living).with(admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void zipNeedsAuth() throws Exception {
        long id = newestId();
        mvc.perform(get("/api/cameras/clips/zip").param("ids", Long.toString(id)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void zipValidates() throws Exception {
        long id = newestId();
        String tooMany = String.join(",", java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(Long::toString).toList());
        mvc.perform(get("/api/cameras/clips/zip").with(admin)).andExpect(status().isBadRequest());
        for (String ids : List.of("", tooMany, "x", id + ",x", id + ",,1", "1.5")) {
            mvc.perform(get("/api/cameras/clips/zip").param("ids", ids).with(admin))
                    .andExpect(status().isBadRequest());
        }
    }

    /** day -> [count, bytes] of a GET /days answer, in order. */
    java.util.LinkedHashMap<String, List<Long>> days(String url) throws Exception {
        JsonNode r = getJson(url);
        assertThat(r.isArray()).isTrue();
        java.util.LinkedHashMap<String, List<Long>> out = new java.util.LinkedHashMap<>();
        r.forEach(d -> out.put(d.get("day").asString(), List.of(d.get("count").asLong(), d.get("bytes").asLong())));
        return out;
    }

    @Test
    void daysEndpointCountsAndSizesPerDayWithFilters() throws Exception {
        // 00:30 local on the 17th is still the 16th in UTC: it counts for the 17th (the cameras' own day)
        clip("Garage", "2026-11-17", "00-30-00", "2026-11-17T00:30:00+01:00", "person");
        clip("Living Room", "2026-11-16", "23-59-00", "2026-11-16T23:59:00+01:00", "motion");
        indexer.reconcile();

        var all = days("/api/cameras/days");
        assertThat(all.keySet()).containsExactly("2026-11-18", "2026-11-17", "2026-11-16"); // newest first
        assertThat(all.get("2026-11-18")).containsExactly(3L, 3000L);
        assertThat(all.get("2026-11-17")).containsExactly(1L, 1000L);
        assertThat(all.get("2026-11-16")).containsExactly(1L, 1000L);

        // each day's bounds, for GET /clips?from=&to= (in the cameras' offset)
        JsonNode first = getJson("/api/cameras/days").get(0);
        assertThat(java.time.OffsetDateTime.parse(first.get("from").asString()))
                .isEqualTo(java.time.OffsetDateTime.parse("2026-11-18T00:00:00+01:00"));
        assertThat(java.time.OffsetDateTime.parse(first.get("to").asString()))
                .isEqualTo(java.time.OffsetDateTime.parse("2026-11-19T00:00:00+01:00"));
        JsonNode firstDayClips = getJson("/api/cameras/clips?from=" + first.get("from").asString().replace("+", "%2B")
                + "&to=" + first.get("to").asString().replace("+", "%2B"));
        assertThat(firstDayClips.get("clips")).hasSize(3);

        var livingRoom = days("/api/cameras/days?camera=Living%20Room");
        assertThat(livingRoom.keySet()).containsExactly("2026-11-18", "2026-11-16");
        assertThat(livingRoom.get("2026-11-16")).containsExactly(1L, 1000L);

        var person = days("/api/cameras/days?trigger=person");
        assertThat(person.keySet()).containsExactly("2026-11-18", "2026-11-17");
        assertThat(person.get("2026-11-18")).containsExactly(2L, 2000L);

        assertThat(days("/api/cameras/days?kept=true")).isEmpty();
        long id = repo.findByStem("Living Room/2026-11-16/23-59-00").orElseThrow().getId();
        mvc.perform(put("/api/cameras/clips/" + id + "/keep").with(admin).with(csrf())).andExpect(status().isOk());
        var kept = days("/api/cameras/days?kept=true");
        assertThat(kept.keySet()).containsExactly("2026-11-16");
        assertThat(days("/api/cameras/days?kept=false").get("2026-11-16")).isNull();
        assertThat(days("/api/cameras/days?camera=Garage&trigger=motion").keySet()).containsExactly("2026-11-18");
    }

    @Test
    void daysNeedsAuth() throws Exception {
        mvc.perform(get("/api/cameras/days")).andExpect(status().isUnauthorized());
    }
}
