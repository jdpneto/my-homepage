package com.davidneto.homepage.cameras;

import com.davidneto.homepage.repository.WebDavUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertCheckerTest {

    static final long MB = 1024L * 1024L;

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    record Sent(String title, String message, String priority) {
    }

    @Autowired CameraClipRepository clips;
    @Autowired AlertStateRepository states;
    @Autowired WebDavUserRepository users;
    @TempDir Path tmp;
    Path root;
    MutableClock clock;
    List<Sent> sent;
    long volumeFree;
    String[] retentionFailure = {null};
    AlertChecker checker;
    ClipService service;
    ClipFiles files;
    RetentionJob retention;
    CameraProperties props;

    @BeforeEach
    void setUp() throws Exception {
        clips.deleteAll();
        states.deleteAll();
        root = tmp.resolve("cameras");
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
        clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
        sent = new ArrayList<>();
        volumeFree = 13_000 * MB;
        props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        props.setRootDiskPath(tmp);
        files = new ClipFiles(props);
        ClipThumbnailer thumbs = new ClipThumbnailer(props, files, (m, j) -> { });
        DiskSpace disk = new DiskSpace() {
            public long total(Path p) { return 15_000 * MB; }
            public long free(Path p) { return p.equals(root) ? volumeFree : 13_000 * MB; }
        };
        service = new ClipService(files, thumbs, clips, users, props, disk, clock);
        ClipIndexer indexer = new ClipIndexer(files, thumbs, clips, clock);
        retention = new RetentionJob(files, clips, thumbs, indexer, props, clock) {
            @Override
            public java.util.Optional<String> lastFailure() {
                return java.util.Optional.ofNullable(retentionFailure[0]);
            }
        };
        Notifier notifier = (title, message, priority) -> {
            sent.add(new Sent(title, message, priority));
            return true;
        };
        checker = new AlertChecker(service, files, retention, states, notifier, props, clock);
        heartbeat(clock.instant(), true, null);
    }

    void heartbeat(Instant at, boolean hubOk, Instant hubErrorSince) throws Exception {
        Files.writeString(root.resolve("heartbeat.json"), """
                {"time":"%s","uploader_version":"0.1.0","hub_ok":%s,"hub_error_since":%s,"cameras":4,"last_error":null}
                """.formatted(at.atOffset(ZoneOffset.ofHours(1)), hubOk,
                hubErrorSince == null ? "null" : "\"" + hubErrorSince.atOffset(ZoneOffset.ofHours(1)) + "\""));
    }

    List<String> titles() {
        return sent.stream().map(Sent::title).toList();
    }

    @Test
    void healthyNoAlerts() {
        checker.check();
        assertThat(sent).isEmpty();
    }

    @Test
    void silentAfter60MinutesSendsOnce() {
        clock.advance(Duration.ofMinutes(59));
        checker.check();
        assertThat(sent).isEmpty();
        clock.advance(Duration.ofMinutes(2));
        checker.check();
        assertThat(titles()).containsExactly("Camera uploader silent");
        assertThat(sent.get(0).priority()).isEqualTo("high");
        clock.advance(Duration.ofMinutes(5));
        checker.check();
        assertThat(sent).hasSize(1);
    }

    @Test
    void missingHeartbeatCountsAsSilent() throws Exception {
        Files.delete(root.resolve("heartbeat.json"));
        checker.check();
        assertThat(titles()).containsExactly("Camera uploader silent");
    }

    @Test
    void reminderAfter24h() {
        clock.advance(Duration.ofMinutes(61));
        checker.check();
        clock.advance(Duration.ofHours(23));
        checker.check();
        assertThat(sent).hasSize(1);
        clock.advance(Duration.ofHours(1));
        checker.check();
        assertThat(titles()).containsExactly("Camera uploader silent", "Camera uploader silent");
    }

    @Test
    void resolvedWhenHeartbeatReturns() throws Exception {
        clock.advance(Duration.ofMinutes(61));
        checker.check();
        heartbeat(clock.instant(), true, null);
        checker.check();
        assertThat(titles()).containsExactly("Camera uploader silent", "Resolved: Camera uploader silent");
        assertThat(sent.get(1).priority()).isEqualTo("default");
        checker.check();
        assertThat(sent).hasSize(2);
    }

    @Test
    void hubDownOnlyAfter60Min() throws Exception {
        heartbeat(clock.instant(), false, clock.instant().minus(Duration.ofMinutes(59)));
        checker.check();
        assertThat(sent).isEmpty();
        heartbeat(clock.instant(), false, clock.instant().minus(Duration.ofMinutes(61)));
        checker.check();
        assertThat(titles()).containsExactly("Camera hub unreachable");
    }

    @Test
    void diskLowFromWarnings() {
        volumeFree = 1_000 * MB; // 93 % used
        checker.check();
        assertThat(titles()).containsExactly("Camera storage almost full");
        assertThat(sent.get(0).message()).contains("volume");
    }

    @Test
    void volumeMissing() throws Exception {
        Files.delete(root.resolve("volume-marker.txt"));
        checker.check();
        assertThat(titles()).contains("Camera volume missing");
        assertThat(titles()).doesNotContain("Camera storage almost full");
    }

    @Test
    void cleanupFailed() {
        retentionFailure[0] = "IllegalStateException: disk error";
        checker.check();
        assertThat(titles()).containsExactly("Camera cleanup failed");
        assertThat(sent.get(0).message()).contains("disk error");
        retentionFailure[0] = null;
        checker.check();
        assertThat(titles()).containsExactly("Camera cleanup failed", "Resolved: Camera cleanup failed");
    }

    @Test
    void stateSurvivesRestart() {
        clock.advance(Duration.ofMinutes(61));
        checker.check();
        // a fresh checker (app restart) must not re-send the same alert
        AlertChecker again = new AlertChecker(service, files, retention, states,
                (t, m, p) -> { sent.add(new Sent(t, m, p)); return true; }, props, clock);
        again.check();
        assertThat(sent).hasSize(1);
    }
}
