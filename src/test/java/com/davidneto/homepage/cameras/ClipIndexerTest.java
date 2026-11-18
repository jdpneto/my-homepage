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
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ClipIndexerTest {

    @Autowired CameraClipRepository repo;
    @TempDir Path tmp;
    Path root;
    ClipFiles files;
    ClipIndexer indexer;

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
        indexer = new ClipIndexer(files, thumbs, repo,
                Clock.fixed(Instant.parse("2026-11-18T21:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void addsClipWithSidecar() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
        ClipIndexer.IndexResult r = indexer.reconcile();
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.volumeOk()).isTrue();
        CameraClip c = repo.findByStem("Garage/2026-11-18/21-45-49").orElseThrow();
        assertThat(c.getCameraName()).isEqualTo("Garage");
        assertThat(c.getCameraId()).isEqualTo("9527000O0NWN1LLO");
        assertThat(c.getStartAt()).isEqualTo(Instant.parse("2026-11-18T20:45:49Z"));
        assertThat(c.getUtcOffsetSeconds()).isEqualTo(3600);
        assertThat(c.getDurationSeconds()).isEqualTo(10);
        assertThat(c.getTriggers()).isEqualTo("person");
        assertThat(c.getSizeBytes()).isEqualTo(4);
        assertThat(c.getThumbPath()).isNotNull();
        assertThat(c.isKept()).isFalse();
        assertThat(indexer.reconcile().added()).isZero(); // idempotent
    }

    @Test
    void skipsMp4WithoutSidecarThenAddsWhenItAppears() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", null);
        assertThat(indexer.reconcile().added()).isZero();
        Files.writeString(root.resolve("Garage/2026-11-18/21-45-49.json"), ClipFilesTest.SIDECAR);
        assertThat(indexer.reconcile().added()).isEqualTo(1);
    }

    @Test
    void skipsTruncatedSidecar() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", "{\"camera_name\":\"Gar");
        assertThat(indexer.reconcile().added()).isZero();
        assertThat(repo.count()).isZero();
    }

    @Test
    void removesRowWhenMp4Deleted() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
        indexer.reconcile();
        Files.delete(root.resolve("Garage/2026-11-18/21-45-49.mp4"));
        ClipIndexer.IndexResult r = indexer.reconcile();
        assertThat(r.removed()).isEqualTo(1);
        assertThat(repo.count()).isZero();
    }

    @Test
    void picksUpKeepMarkerChanges() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
        indexer.reconcile();
        files.setKept("Garage/2026-11-18/21-45-49", true);
        assertThat(indexer.reconcile().keptChanged()).isEqualTo(1);
        CameraClip c = repo.findByStem("Garage/2026-11-18/21-45-49").orElseThrow();
        assertThat(c.isKept()).isTrue();
        assertThat(c.getKeptAt()).isEqualTo(Instant.parse("2026-11-18T21:00:00Z"));
        files.setKept("Garage/2026-11-18/21-45-49", false);
        indexer.reconcile();
        assertThat(repo.findByStem("Garage/2026-11-18/21-45-49").orElseThrow().isKept()).isFalse();
    }

    @Test
    void keptStateAtFirstIndexComesFromDisk() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
        files.setKept("Garage/2026-11-18/21-45-49", true);
        indexer.reconcile();
        assertThat(repo.findByStem("Garage/2026-11-18/21-45-49").orElseThrow().isKept()).isTrue();
    }

    @Test
    void volumeMissingTouchesNothing() throws Exception {
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
        indexer.reconcile();
        Files.delete(root.resolve("volume-marker.txt"));
        Files.delete(root.resolve("Garage/2026-11-18/21-45-49.mp4"));
        ClipIndexer.IndexResult r = indexer.reconcile();
        assertThat(r.volumeOk()).isFalse();
        assertThat(r.removed()).isZero();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void ignoresMarkerHeartbeatAndKeepFiles() throws Exception {
        Files.writeString(root.resolve("heartbeat.json"), "{\"time\":\"2026-11-18T21:50:00+01:00\"}");
        Files.createDirectories(root.resolve("Garage/2026-11-18"));
        Files.createFile(root.resolve("Garage/2026-11-18/21-45-49.keep"));
        assertThat(indexer.reconcile().added()).isZero();
        assertThat(repo.count()).isZero();
    }
}
