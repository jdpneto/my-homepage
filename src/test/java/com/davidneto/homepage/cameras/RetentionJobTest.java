package com.davidneto.homepage.cameras;

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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RetentionJobTest {

    static final Instant NOW = Instant.parse("2026-12-20T03:30:00Z");

    @Autowired CameraClipRepository repo;
    @TempDir Path tmp;
    Path root;
    ClipFiles files;
    ClipIndexer indexer;
    RetentionJob job;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        root = tmp.resolve("cameras");
        Files.createDirectories(root);
        Files.writeString(root.resolve("volume-marker.txt"), ClipFilesTest.MARKER_TEXT);
        CameraProperties props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        files = new ClipFiles(props);
        ClipThumbnailer thumbs = new ClipThumbnailer(props, files, (mp4, jpg) -> Files.write(jpg, new byte[]{1}));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        indexer = new ClipIndexer(files, thumbs, repo, clock);
        job = new RetentionJob(files, repo, thumbs, indexer, props, clock);
    }

    String clipDaysAgo(double days) throws Exception {
        OffsetDateTime start = NOW.minusSeconds((long) (days * 86400)).atOffset(ZoneOffset.ofHours(1));
        String stem = "Garage/" + start.toLocalDate() + "/" + String.format("%02d-%02d-%02d",
                start.getHour(), start.getMinute(), start.getSecond());
        ClipFilesTest.clip(root, stem, """
                {"camera_name":"Garage","camera_id":"g","channel":0,"start":"%s","end":"%s",
                 "duration_seconds":10,"triggers":["person"]}""".formatted(start, start.plusSeconds(10)));
        return stem;
    }

    @Test
    void deletesOnlyOlderThan30Days() throws Exception {
        String old = clipDaysAgo(31);
        String young = clipDaysAgo(29);
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
        RetentionJob flaky = new RetentionJob(files, repo, thumbs, indexer, props, clock) {
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
}
