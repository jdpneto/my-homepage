package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.davidneto.homepage.entity.WebDavUser;
import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RetentionJobTest {

    static final Instant NOW = Instant.parse("2026-12-20T03:30:00Z");

    record Sent(String title, String message, String priority) {
    }

    @Autowired CameraClipRepository repo;
    @Autowired CameraSettingRepository settings;
    @Autowired WebDavUserRepository users;
    @Autowired CameraProperties boundProps;
    @TempDir Path tmp;
    Path root;
    ClipFiles files;
    ClipIndexer indexer;
    RetentionJob job;
    CameraProperties props;
    ClipThumbnailer thumbs;
    AlertCheckerTest.MutableClock clock;
    ClipService service;
    Notifier notifier;
    AlertMute mute;
    List<Sent> sent;
    // The volume as the fake disk reports it (the quota is measured on the files themselves).
    long volumeTotal;
    long volumeFree;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        settings.deleteAll();
        users.findByUsername("cameras").ifPresent(users::delete);
        root = tmp.resolve("cameras");
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
        props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        props.setRootDiskPath(tmp);
        files = new ClipFiles(props);
        thumbs = new ClipThumbnailer(props, files, (mp4, jpg) -> Files.write(jpg, new byte[]{1}));
        clock = new AlertCheckerTest.MutableClock(NOW);
        indexer = new ClipIndexer(files, thumbs, repo, clock);
        volumeTotal = 100_000;
        volumeFree = 60_000; // 40 % used
        DiskSpace disk = new DiskSpace() {
            public long total(Path p) { return p.equals(root) ? volumeTotal : 1_000_000_000L; }
            public long free(Path p) { return p.equals(root) ? volumeFree : 900_000_000L; }
        };
        service = new ClipService(files, thumbs, repo, users, props, disk, clock);
        sent = new ArrayList<>();
        notifier = (title, message, priority) -> {
            sent.add(new Sent(title, message, priority));
            return true;
        };
        mute = new AlertMute(settings, clock);
        job = newJob();
    }

    /** A restart: the push limits live in camera_setting, not in the job. */
    RetentionJob newJob() {
        return new RetentionJob(files, repo, thumbs, indexer, props, clock, service, notifier, mute, settings);
    }

    @AfterEach
    void tearDown() {
        users.findByUsername("cameras").ifPresent(users::delete);
        repo.deleteAll();
        settings.deleteAll();
    }

    String clipDaysAgo(double days) throws Exception {
        return clipDaysAgo(days, days);
    }

    String clipDaysAgo(double days, double arrivedDaysAgo) throws Exception {
        return clipDaysAgo(days, arrivedDaysAgo, 4);
    }

    /**
     * A clip recorded {@code days} ago (hub clock) that arrived on the VPS {@code arrivedDaysAgo}
     * ago, its video {@code size} bytes.
     */
    String clipDaysAgo(double days, double arrivedDaysAgo, int size) throws Exception {
        OffsetDateTime start = NOW.minusSeconds((long) (days * 86400)).atOffset(ZoneOffset.ofHours(1));
        String stem = "Garage/" + start.toLocalDate() + "/" + String.format("%02d-%02d-%02d",
                start.getHour(), start.getMinute(), start.getSecond());
        ClipFilesTest.clip(root, stem, """
                {"camera_name":"Garage","camera_id":"g","channel":0,"start":"%s","end":"%s",
                 "duration_seconds":10,"triggers":["person"]}""".formatted(start, start.plusSeconds(10)));
        Files.write(root.resolve(stem + ".mp4"), new byte[size]);
        java.nio.file.attribute.FileTime arrived = java.nio.file.attribute.FileTime.from(
                NOW.minusSeconds((long) (arrivedDaysAgo * 86400)));
        Files.setLastModifiedTime(root.resolve(stem + ".mp4"), arrived);
        return stem;
    }

    @Test
    void defaultRetentionIs14Days() throws Exception {
        assertThat(new CameraProperties().getRetentionDays()).isEqualTo(14);
        assertThat(boundProps.getRetentionDays()).isEqualTo(14); // nothing in application.yml overrides it
        String old = clipDaysAgo(14 + 1.0 / 24);
        String young = clipDaysAgo(14 - 1.0 / 24);
        assertThat(job.run().deleted()).isEqualTo(1);
        assertThat(root.resolve(old + ".mp4")).doesNotExist();
        assertThat(root.resolve(young + ".mp4")).exists();
    }

    @Test
    void deletesOnlyOlderThan14Days() throws Exception {
        String old = clipDaysAgo(15);
        String young = clipDaysAgo(13);
        RetentionJob.RetentionResult r = job.run();
        assertThat(r.ran()).isTrue();
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(root.resolve(old + ".mp4")).doesNotExist();
        assertThat(root.resolve(old + ".json")).doesNotExist();
        assertThat(root.resolve(young + ".mp4")).exists();
        assertThat(repo.findByStem(old)).isEmpty();
        assertThat(repo.findByStem(young)).isPresent();
    }

    @Test
    void neverDeletesKeptEvenIfRowSaysNotKept() throws Exception {
        String old = clipDaysAgo(40);
        indexer.reconcile();
        files.setKept(old, true); // marker written after the index was built: row still says not kept
        CameraClip row = repo.findByStem(old).orElseThrow();
        row.setKept(false);
        repo.save(row);
        RetentionJob.RetentionResult r = job.run(false);
        assertThat(r.deleted()).isZero();
        assertThat(r.skippedKept()).isEqualTo(1);
        assertThat(root.resolve(old + ".mp4")).exists();
        assertThat(repo.findByStem(old).orElseThrow().isKept()).isTrue();
    }

    @Test
    void unkeptOldClipDeletedOnNextRun() throws Exception {
        String old = clipDaysAgo(45);
        files.setKept(old, true);
        assertThat(job.run().deleted()).isZero();
        files.setKept(old, false);
        assertThat(job.run().deleted()).isEqualTo(1);
        assertThat(root.resolve(old + ".mp4")).doesNotExist();
        assertThat(root.resolve(old + ".keep")).doesNotExist();
    }

    @Test
    void volumeMissingDeletesNothing() throws Exception {
        String old = clipDaysAgo(31);
        indexer.reconcile();
        Files.delete(root.resolve("volume-marker.txt"));
        RetentionJob.RetentionResult r = job.run();
        assertThat(r.ran()).isFalse();
        assertThat(r.reason()).contains("volume");
        assertThat(root.resolve(old + ".mp4")).exists();
    }

    @Test
    void emptyIndexWithFilesDeletesNothing() throws Exception {
        String old = clipDaysAgo(31);
        // Files present but the index has nothing (e.g. every sidecar unreadable): do not act blindly.
        Files.writeString(root.resolve(old + ".json"), "{broken");
        RetentionJob.RetentionResult r = job.run();
        assertThat(r.ran()).isFalse();
        assertThat(r.reason()).contains("index");
        assertThat(root.resolve(old + ".mp4")).exists();
    }

    @Test
    void neverTouchesMarkerOrHeartbeat() throws Exception {
        clipDaysAgo(60);
        Files.writeString(root.resolve("heartbeat.json"), "{}");
        Files.setLastModifiedTime(root.resolve("heartbeat.json"), java.nio.file.attribute.FileTime.from(NOW.minusSeconds(90L * 86400)));
        job.run();
        assertThat(root.resolve("volume-marker.txt")).exists();
        assertThat(root.resolve("heartbeat.json")).exists();
    }

    @Test
    void scheduledRunRecordsAndClearsFailure() throws Exception {
        CameraProperties props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        ClipThumbnailer thumbs = new ClipThumbnailer(props, files, (mp4, jpg) -> { });
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        boolean[] fail = {true};
        RetentionJob flaky = new RetentionJob(files, repo, thumbs, indexer, props, clock, service, notifier, mute, settings) {
            @Override
            RetentionResult run(boolean reconcileFirst) {
                if (fail[0]) throw new IllegalStateException("disk error");
                return super.run(reconcileFirst);
            }
        };
        assertThat(flaky.lastFailure()).isEmpty();
        flaky.scheduled();
        assertThat(flaky.lastFailure()).hasValueSatisfying(m -> assertThat(m).contains("disk error"));
        fail[0] = false;
        flaky.scheduled();
        assertThat(flaky.lastFailure()).isEmpty();
    }

    @Test
    void wrongHubClockNeverDeletesNewArrivals() throws Exception {
        // hub booted with a wrong date: clip stamped 400 days ago but it arrived today
        String stem = clipDaysAgo(400, 0);
        assertThat(job.run().deleted()).isZero();
        assertThat(root.resolve(stem + ".mp4")).exists();
    }

    @Test
    void boundaryFollowsTheSetting() throws Exception {
        props.setRetentionDays(30);
        String justInside = clipDaysAgo(30 - 1.0 / 24);
        String justOutside = clipDaysAgo(30 + 1.0 / 24);
        job.run();
        assertThat(root.resolve(justInside + ".mp4")).exists();
        assertThat(root.resolve(justOutside + ".mp4")).doesNotExist();
    }

    @Test
    void orphanMp4WithoutSidecarIsCleanedWhenOldAndNotKept() throws Exception {
        String indexed = clipDaysAgo(5); // keeps the index non-empty
        ClipFilesTest.clip(root, "Garage/2026-10-01/10-00-00", null);
        Files.setLastModifiedTime(root.resolve("Garage/2026-10-01/10-00-00.mp4"),
                java.nio.file.attribute.FileTime.from(NOW.minusSeconds(40L * 86400)));
        ClipFilesTest.clip(root, "Garage/2026-10-02/10-00-00", null);
        Files.setLastModifiedTime(root.resolve("Garage/2026-10-02/10-00-00.mp4"),
                java.nio.file.attribute.FileTime.from(NOW.minusSeconds(40L * 86400)));
        files.setKept("Garage/2026-10-02/10-00-00", true);
        ClipFilesTest.clip(root, "Garage/2026-12-19/10-00-00", null); // young orphan (still uploading)
        Files.setLastModifiedTime(root.resolve("Garage/2026-12-19/10-00-00.mp4"), java.nio.file.attribute.FileTime.from(NOW));
        job.run();
        assertThat(root.resolve("Garage/2026-10-01/10-00-00.mp4")).doesNotExist();
        assertThat(root.resolve("Garage/2026-10-02/10-00-00.mp4")).exists();
        assertThat(root.resolve("Garage/2026-12-19/10-00-00.mp4")).exists();
        assertThat(root.resolve(indexed + ".mp4")).exists();
    }

    // ---------------------------------------------------------------- safety net (85 % -> below 75 %)

    @Test
    void safetyNetDeletesOldestUnkeptUntilBelow75() throws Exception {
        // 90 % used: 10 000 bytes over 75 % plus 5 000 -> more than 15 000 bytes must go
        volumeFree = 10_000;
        String keptOldest = clipDaysAgo(12, 12, 6_000);
        files.setKept(keptOldest, true);
        String d10 = clipDaysAgo(10, 10, 6_000), d9 = clipDaysAgo(9, 9, 6_000), d8 = clipDaysAgo(8, 8, 6_000);
        String d7 = clipDaysAgo(7, 7, 6_000), d1 = clipDaysAgo(1, 1, 6_000);
        indexer.reconcile();

        RetentionJob.SafetyNetResult r = job.safetyNet();
        assertThat(r.ran()).isTrue();
        assertThat(r.deleted()).isEqualTo(3); // 12 000 bytes are not enough, 18 000 are
        assertThat(r.freedBytes()).isEqualTo(18_000);
        for (String gone : List.of(d10, d9, d8)) {
            assertThat(root.resolve(gone + ".mp4")).as(gone).doesNotExist();
            assertThat(root.resolve(gone + ".json")).as(gone).doesNotExist();
            assertThat(repo.findByStem(gone)).as(gone).isEmpty();
        }
        for (String stays : List.of(keptOldest, d7, d1)) {
            assertThat(root.resolve(stays + ".mp4")).as(stays).exists();
            assertThat(repo.findByStem(stays)).as(stays).isPresent();
        }

        // the cameras quota counts too: 1 MB quota, ~86 % used by the files, the volume itself at 40 %
        volumeFree = 60_000;
        WebDavUser u = new WebDavUser();
        u.setUsername("cameras");
        u.setPasswordHash("x");
        u.setStorageQuotaMb(1);
        users.save(u);
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 9; i++) big.add(clipDaysAgo(6 - i * 0.1, 6 - i * 0.1, 100_000));
        indexer.reconcile();
        RetentionJob.SafetyNetResult q = job.safetyNet();
        assertThat(q.deleted()).isEqualTo(3); // d7 (6 000) + the two oldest 100 000-byte clips
        assertThat(root.resolve(d7 + ".mp4")).doesNotExist();
        assertThat(root.resolve(big.get(0) + ".mp4")).doesNotExist();
        assertThat(root.resolve(big.get(1) + ".mp4")).doesNotExist();
        assertThat(root.resolve(big.get(2) + ".mp4")).exists();
        assertThat(root.resolve(keptOldest + ".mp4")).exists();
    }

    @Test
    void safetyNetNeverDeletesKept() throws Exception {
        volumeFree = 1_000; // 99 % used, and only kept clips (one kept on disk only: the row says not kept)
        String a = clipDaysAgo(20, 20, 6_000), b = clipDaysAgo(10, 10, 6_000);
        indexer.reconcile();
        files.setKept(a, true);
        files.setKept(b, true);
        CameraClip row = repo.findByStem(a).orElseThrow();
        assertThat(row.isKept()).isFalse();

        RetentionJob.SafetyNetResult r = job.safetyNet();
        assertThat(r.ran()).isTrue();
        assertThat(r.deleted()).isZero();
        assertThat(root.resolve(a + ".mp4")).exists();
        assertThat(root.resolve(b + ".mp4")).exists();
        assertThat(repo.findByStem(a).orElseThrow().isKept()).isTrue(); // the row follows the file
        assertThat(sent).singleElement().satisfies(m -> // nothing freed, but David must know why the disk is full
                assertThat(m.title()).isEqualTo("Camera disk full of kept clips or other data"));
    }

    @Test
    void safetyNetDeletesNothingWhenDeletingEveryUnkeptClipCannotGetBelow85() throws Exception {
        // 90 % used, but kept clips and other files on the volume are 88 %: the 2 000 bytes of
        // clips that are not kept (one of them recorded minutes ago) cannot help - keep them
        volumeFree = 10_000;
        String older = clipDaysAgo(2, 2, 1_000);
        String today = clipDaysAgo(0.01, 0.01, 1_000);
        indexer.reconcile();

        RetentionJob.SafetyNetResult r = job.safetyNet();
        assertThat(r.ran()).isTrue();
        assertThat(r.deleted()).isZero();
        assertThat(root.resolve(older + ".mp4")).exists();
        assertThat(root.resolve(today + ".mp4")).exists();
        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("Camera disk full of kept clips or other data");
            assertThat(m.message()).contains("volume 90% used").contains("75%");
        });

        // every 10 min it finds the same: one alert a day, not 144
        for (int i = 1; i <= 6 * 23; i++) {
            clock.advance(java.time.Duration.ofMinutes(10));
            job.safetyNet();
        }
        newJob().safetyNet(); // a restart does not reset the limit
        assertThat(sent).hasSize(1);
        clock.now = NOW.plus(java.time.Duration.ofHours(24));
        job.safetyNet();
        assertThat(sent).hasSize(2);
        assertThat(root.resolve(today + ".mp4")).exists();
    }

    @Test
    void safetyNetOnlyGetsUnder85WhenBelow75IsOutOfReach() throws Exception {
        // 90 % used; the 12 000 bytes not kept would only get it to 78 %: delete just enough to
        // get under 85 % (the oldest clip), not every clip down to the one recorded minutes ago
        volumeFree = 10_000;
        String oldest = clipDaysAgo(3, 3, 6_000);
        String today = clipDaysAgo(0.01, 0.01, 6_000);
        indexer.reconcile();

        RetentionJob.SafetyNetResult r = job.safetyNet();
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(root.resolve(oldest + ".mp4")).doesNotExist();
        assertThat(root.resolve(today + ".mp4")).exists();
        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("Camera disk full of kept clips or other data");
            assertThat(m.message()).contains("deleted 1 oldest clip (5.9 KB)");
        });
    }

    @Test
    void safetyNetIdleBelow85() throws Exception {
        String a = clipDaysAgo(10, 10, 6_000);
        indexer.reconcile();
        for (long free : new long[]{60_000, 16_000, 15_000}) { // 40 %, 84 %, exactly 85 %
            volumeFree = free;
            RetentionJob.SafetyNetResult r = job.safetyNet();
            assertThat(r.deleted()).as("free %d", free).isZero();
        }
        assertThat(root.resolve(a + ".mp4")).exists();
        assertThat(sent).isEmpty();

        // the volume missing: never acts
        volumeFree = 1_000;
        Files.delete(root.resolve("volume-marker.txt"));
        assertThat(job.safetyNet().ran()).isFalse();
        assertThat(root.resolve(a + ".mp4")).exists();
    }

    @Test
    void safetyNetAlertSent() throws Exception {
        volumeFree = 10_000;
        for (int d = 10; d >= 6; d--) clipDaysAgo(d, d, 6_000);
        indexer.reconcile();
        job.safetyNet();
        assertThat(sent).singleElement().satisfies(m ->
                assertThat(m.title()).isEqualTo("Disk safety net freed 17.6 KB (3 oldest clips)"));

        // over 85 % again 10 min later: deletes again, but one push a day is enough (also after a restart)
        for (int d = 5; d >= 3; d--) clipDaysAgo(d, d, 6_000);
        indexer.reconcile();
        clock.advance(java.time.Duration.ofMinutes(10));
        volumeFree = 10_000;
        assertThat(newJob().safetyNet().deleted()).isEqualTo(3);
        assertThat(sent).hasSize(1);
        // a day later it may push again
        for (int d = 2; d >= 0; d--) clipDaysAgo(d + 0.5, d + 0.5, 6_000);
        indexer.reconcile();
        clock.now = NOW.plus(java.time.Duration.ofHours(24));
        volumeFree = 10_000;
        assertThat(job.safetyNet().deleted()).isEqualTo(3);
        assertThat(sent).hasSize(2);

        // muted: still deletes, sends nothing
        sent.clear();
        mute.set(true, null);
        clock.now = NOW.plus(java.time.Duration.ofHours(72));
        for (int d = 2; d >= 0; d--) clipDaysAgo(d + 0.25, d + 0.25, 6_000);
        indexer.reconcile();
        volumeFree = 10_000;
        assertThat(job.safetyNet().deleted()).isEqualTo(3);
        assertThat(sent).isEmpty();
    }

    @Test
    void scheduledCleanupAlsoRunsTheSafetyNet() throws Exception {
        volumeFree = 10_000;
        String young = clipDaysAgo(3, 3, 20_000);
        indexer.reconcile();
        job.scheduled();
        assertThat(root.resolve(young + ".mp4")).doesNotExist();
        assertThat(job.lastFailure()).isEmpty();
    }
}
