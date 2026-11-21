package com.davidneto.homepage.cameras;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class ClipServiceTest {

    static final long MB = 1024L * 1024L;

    @Autowired CameraClipRepository repo;
    @Autowired WebDavUserRepository users;
    @TempDir Path tmp;
    Path root;
    CameraProperties props;
    ClipFiles files;
    ClipIndexer indexer;
    ClipService service;
    long volumeTotal = 15_000 * MB, volumeFree = 13_000 * MB, rootTotal = 75_000 * MB, rootFree = 24_000 * MB;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        users.findByUsername("cameras").ifPresent(users::delete);
        root = tmp.resolve("cameras");
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
        props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        props.setRootDiskPath(tmp);
        files = new ClipFiles(props);
        ClipThumbnailer thumbs = new ClipThumbnailer(props, files, (mp4, jpg) -> Files.write(jpg, new byte[]{1}));
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T21:00:00Z"), ZoneOffset.UTC);
        indexer = new ClipIndexer(files, thumbs, repo, clock);
        DiskSpace disk = new DiskSpace() {
            public long total(Path p) { return p.equals(root) ? volumeTotal : rootTotal; }
            public long free(Path p) { return p.equals(root) ? volumeFree : rootFree; }
        };
        service = new ClipService(files, thumbs, repo, users, props, disk, clock);
    }

    @AfterEach
    void tearDown() {
        users.findByUsername("cameras").ifPresent(users::delete);
        repo.deleteAll(); // leave no rows for other classes sharing the test database
    }

    void clip(String camera, String date, String time, String start, List<String> triggers) throws Exception {
        String sidecar = """
                {"camera_name": "%s", "camera_id": "id-%s", "channel": 0,
                 "start": "%s", "end": "%s", "duration_seconds": 10, "triggers": [%s]}
                """.formatted(camera, camera, start, OffsetDateTime.parse(start).plusSeconds(10),
                String.join(",", triggers.stream().map(t -> "\"" + t + "\"").toList()));
        ClipFilesTest.clip(root, camera + "/" + date + "/" + time, sidecar);
    }

    void fiveClips() throws Exception {
        clip("Garage", "2026-11-18", "21-45-49", "2026-11-18T21:45:49+01:00", List.of("person"));
        clip("Garage", "2026-11-18", "21-49-41", "2026-11-18T21:49:41+01:00", List.of("motion"));
        clip("Living Room", "2026-11-18", "20-00-00", "2026-11-18T20:00:00+01:00", List.of("person", "motion"));
        clip("Backyard", "2026-11-17", "19-23-30", "2026-11-17T19:23:30+01:00", List.of("person"));
        clip("Backyard", "2026-11-16", "19-22-58", "2026-11-16T19:22:58+01:00", List.of("animal"));
        indexer.reconcile();
    }

    ClipQuery all(int limit) {
        return new ClipQuery(null, null, null, null, null, null, limit);
    }

    @Test
    void listNewestFirstWithKeysetPaging() throws Exception {
        fiveClips();
        List<ClipView> seen = new ArrayList<>();
        ClipCursor before = null;
        for (int i = 0; i < 4; i++) {
            List<ClipView> page = service.list(new ClipQuery(null, null, null, null, null, before, 2));
            if (page.isEmpty()) break;
            seen.addAll(page);
            before = ClipCursor.of(page.get(page.size() - 1));
        }
        assertThat(seen).hasSize(5);
        assertThat(new HashSet<>(seen.stream().map(ClipView::id).toList())).hasSize(5);
        assertThat(seen.get(0).start()).isEqualTo(OffsetDateTime.parse("2026-11-18T21:49:41+01:00"));
        assertThat(seen.get(4).start()).isEqualTo(OffsetDateTime.parse("2026-11-16T19:22:58+01:00"));
        assertThat(seen.get(0).start().getOffset()).isEqualTo(ZoneOffset.ofHours(1));
    }

    @Test
    void filtersByCameraWithSpaceTriggerKeptAndRange() throws Exception {
        fiveClips();
        assertThat(service.list(new ClipQuery("Living Room", null, null, null, null, null, 50)))
                .extracting(ClipView::camera).containsExactly("Living Room");
        assertThat(service.list(new ClipQuery(null, "person", null, null, null, null, 50))).hasSize(3);
        assertThat(service.list(new ClipQuery(null, "motion", null, null, null, null, 50))).hasSize(2);
        assertThat(service.list(new ClipQuery(null, "mot", null, null, null, null, 50))).isEmpty();
        long id = service.list(all(1)).get(0).id();
        service.keep(id, true);
        assertThat(service.list(new ClipQuery(null, null, null, null, true, null, 50)))
                .extracting(ClipView::id).containsExactly(id);
        assertThat(service.list(new ClipQuery(null, null, OffsetDateTime.parse("2026-11-17T00:00:00+01:00"),
                OffsetDateTime.parse("2026-11-18T00:00:00+01:00"), null, null, 50)))
                .extracting(ClipView::camera).containsExactly("Backyard");
        assertThat(service.cameras()).extracting(CameraInfo::name).containsExactly("Backyard", "Garage", "Living Room");
        assertThat(service.cameras()).extracting(CameraInfo::cameraId).containsExactly("id-Backyard", "id-Garage", "id-Living Room");
    }

    @Test
    void keepWritesMarkerAndRow() throws Exception {
        fiveClips();
        ClipView v = service.list(all(1)).get(0);
        assertThat(service.keep(v.id(), true).kept()).isTrue();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).exists();
        assertThat(service.keep(v.id(), false).kept()).isFalse();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).doesNotExist();
    }

    @Test
    void deleteRefusesKept() throws Exception {
        fiveClips();
        long id = service.list(all(1)).get(0).id();
        service.keep(id, true);
        assertThrows(ClipKeptException.class, () -> service.delete(id));
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).exists();
    }

    @Test
    void deleteRemovesFilesThumbAndRow() throws Exception {
        fiveClips();
        long id = service.list(all(1)).get(0).id();
        Path thumb = service.thumbPath(id).orElseThrow();
        service.delete(id);
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).doesNotExist();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.json")).doesNotExist();
        assertThat(thumb).doesNotExist();
        assertThat(service.get(id)).isEmpty();
    }

    @Test
    void videoPathIsTheMp4() throws Exception {
        fiveClips();
        long id = service.list(all(1)).get(0).id();
        assertThat(service.videoPath(id)).isEqualTo(root.resolve("Garage/2026-11-18/21-49-41.mp4").toAbsolutePath().normalize());
    }

    @Test
    void statusComputesKeptVsUnkeptAndWarnings() throws Exception {
        fiveClips(); // 5 clips × 4 bytes
        service.keep(service.list(all(1)).get(0).id(), true);
        WebDavUser u = new WebDavUser();
        u.setUsername("cameras");
        u.setPasswordHash("x");
        u.setStorageQuotaMb(1);
        users.save(u);
        Files.write(root.resolve("Garage/big.bin"), new byte[(int) (0.9 * MB)]);
        volumeFree = 1_000 * MB; // 93 % used
        StorageStatus s = service.status();
        assertThat(s.volumeOk()).isTrue();
        assertThat(s.keptBytes()).isEqualTo(4);
        assertThat(s.unkeptBytes()).isEqualTo(16);
        assertThat(s.quotaBytes()).isEqualTo(MB);
        assertThat(s.quotaUsed()).isGreaterThan((long) (0.85 * MB));
        assertThat(s.volumeTotal()).isEqualTo(volumeTotal);
        assertThat(s.rootFree()).isEqualTo(rootFree);
        assertThat(s.warnings()).anyMatch(w -> w.contains("quota")).anyMatch(w -> w.contains("volume"));
        assertThat(s.warnings()).noneMatch(w -> w.contains("root"));
        assertThat(s.newestByCamera()).containsEntry("Garage", OffsetDateTime.parse("2026-11-18T21:49:41+01:00"));
    }

    @Test
    void statusNoWarningsWhenHealthy() throws Exception {
        fiveClips();
        assertThat(service.status().warnings()).isEmpty();
    }

    @Test
    void statusVolumeMissing() throws Exception {
        Files.delete(root.resolve("volume-marker.txt"));
        StorageStatus s = service.status();
        assertThat(s.volumeOk()).isFalse();
        assertThat(s.warnings()).anyMatch(w -> w.contains("volume missing"));
    }

    @Test
    void statusReadsHeartbeat() throws Exception {
        Files.writeString(root.resolve("heartbeat.json"),
                "{\"time\":\"2026-11-18T21:55:00+01:00\",\"hub_ok\":false,\"hub_error_since\":\"2026-11-18T21:00:00+01:00\"}");
        StorageStatus s = service.status();
        assertThat(s.heartbeatAt()).isEqualTo(OffsetDateTime.parse("2026-11-18T21:55:00+01:00"));
        assertThat(s.hubOk()).isFalse();
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(service.get(999_999L)).isEmpty();
        assertThat(Map.of()).isEmpty();
    }

    @Test
    void deleteChecksKeepOnDiskNotTheCachedFlag() throws Exception {
        fiveClips();
        ClipView v = service.list(all(1)).get(0);
        files.setKept("Garage/2026-11-18/21-49-41", true); // row still says kept=false
        assertThrows(ClipKeptException.class, () -> service.delete(v.id()));
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).exists();
    }

    @Test
    void cameraSummariesHaveCountsAndLatest() throws Exception {
        fiveClips();
        List<CameraSummary> s = service.cameraSummaries();
        assertThat(s).extracting(CameraSummary::name).containsExactly("Backyard", "Garage", "Living Room");
        CameraSummary garage = s.get(1);
        assertThat(garage.clipCount()).isEqualTo(2);
        assertThat(garage.latestStart()).isEqualTo(OffsetDateTime.parse("2026-11-18T21:49:41+01:00"));
        assertThat(service.get(garage.latestClipId())).hasValueSatisfying(v -> assertThat(v.camera()).isEqualTo("Garage"));
    }

    @Test
    void deleteManyDeletesSkipsKeptAndReportsUnknown() throws Exception {
        fiveClips();
        List<ClipView> all = service.list(all(10));
        ClipView a = all.get(0), kept = all.get(1), b = all.get(2);
        service.keep(kept.id(), true);
        BulkDeleteResult r = service.deleteMany(List.of(a.id(), kept.id(), b.id(), 999999L, a.id()));
        assertThat(r.deleted()).containsExactly(a.id(), b.id());
        assertThat(r.skippedKept()).containsExactly(kept.id());
        assertThat(r.notFound()).containsExactly(999999L);
        assertThat(r.failed()).isEmpty();
        assertThat(r.freedBytes()).isEqualTo(a.sizeBytes() + b.sizeBytes()).isPositive();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).doesNotExist();
        assertThat(root.resolve("Living Room/2026-11-18/20-00-00.mp4")).doesNotExist();
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.mp4")).exists();
        assertThat(service.get(a.id())).isEmpty();
        assertThat(service.get(kept.id())).isPresent();
    }

    @Test
    void bulkDeleteSkipsKeptByTheFileNotTheRow() throws Exception {
        fiveClips();
        ClipView c = service.list(all(1)).get(0);
        files.setKept(repo.findById(c.id()).orElseThrow().getStem(), true); // kept elsewhere; row still says not kept
        BulkDeleteResult r = service.deleteMany(List.of(c.id()));
        assertThat(r.deleted()).isEmpty();
        assertThat(r.skippedKept()).containsExactly(c.id());
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.mp4")).exists();
    }

    @Test
    void deleteManyContinuesPastAFailedClip() throws Exception {
        fiveClips();
        List<Long> ids = service.list(all(10)).stream().map(ClipView::id).toList();
        Path day = root.resolve("Backyard/2026-11-16");
        Files.setPosixFilePermissions(day, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            BulkDeleteResult r = service.deleteMany(ids);
            assertThat(r.failed()).containsExactly(ids.get(4));
            assertThat(r.deleted()).containsExactlyElementsOf(ids.subList(0, 4));
            assertThat(day.resolve("19-22-58.mp4")).exists();
            assertThat(service.get(ids.get(4))).isPresent();
        } finally {
            Files.setPosixFilePermissions(day, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void deletesAreRefusedWhileTheVolumeIsMissing() throws Exception {
        fiveClips();
        List<Long> ids = service.list(all(10)).stream().map(ClipView::id).toList();
        Files.delete(root.resolve("volume-marker.txt")); // what an empty mount point looks like
        assertThrows(VolumeUnavailableException.class, () -> service.deleteMany(ids));
        assertThrows(VolumeUnavailableException.class, () -> service.delete(ids.get(0)));
        assertThat(service.list(all(10))).hasSize(5);
    }

    @Test
    void keepManyKeepsAndReportsUnknown() throws Exception {
        fiveClips();
        List<ClipView> all = service.list(all(10));
        ClipView a = all.get(0), already = all.get(1), b = all.get(2);
        service.keep(already.id(), true);
        BulkKeepResult r = service.keepMany(List.of(a.id(), already.id(), b.id(), 999999L, a.id()));
        assertThat(r.kept()).containsExactly(a.id(), already.id(), b.id());
        assertThat(r.notFound()).containsExactly(999999L);
        assertThat(r.failed()).isEmpty();
        assertThat(r.keptBytes()).isEqualTo(a.sizeBytes() + already.sizeBytes() + b.sizeBytes()).isPositive();
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).exists();
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.keep")).exists();
        assertThat(root.resolve("Living Room/2026-11-18/20-00-00.keep")).exists();
        assertThat(root.resolve("Backyard/2026-11-17/19-23-30.keep")).doesNotExist();
        assertThat(service.get(a.id()).orElseThrow().kept()).isTrue();
        assertThat(service.get(b.id()).orElseThrow().kept()).isTrue();
        assertThat(service.get(all.get(3).id()).orElseThrow().kept()).isFalse();
    }

    @Test
    void keepManyContinuesPastAFailedClip() throws Exception {
        fiveClips();
        List<Long> ids = service.list(all(10)).stream().map(ClipView::id).toList();
        Path day = root.resolve("Backyard/2026-11-16");
        Files.setPosixFilePermissions(day, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            BulkKeepResult r = service.keepMany(ids);
            assertThat(r.failed()).containsExactly(ids.get(4));
            assertThat(r.kept()).containsExactlyElementsOf(ids.subList(0, 4));
            assertThat(service.get(ids.get(4)).orElseThrow().kept()).isFalse();
        } finally {
            Files.setPosixFilePermissions(day, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void keepManyRefusedWhileVolumeMissing() throws Exception {
        fiveClips();
        List<Long> ids = service.list(all(10)).stream().map(ClipView::id).toList();
        Files.delete(root.resolve("volume-marker.txt"));
        assertThrows(VolumeUnavailableException.class, () -> service.keepMany(ids));
        assertThat(service.list(all(10))).allMatch(c -> !c.kept());
        assertThat(root.resolve("Garage/2026-11-18/21-49-41.keep")).doesNotExist();
    }
}
