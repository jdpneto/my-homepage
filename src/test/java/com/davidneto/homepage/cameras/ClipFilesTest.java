package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClipFilesTest {

    static final String MARKER_TEXT = "camera clips live on the 15 GB volume - do not delete\n";
    static final String SIDECAR = """
            {
              "camera_name": "Garage",
              "camera_id": "9527000O0NWN1LLO",
              "channel": 0,
              "start": "2026-11-18T21:45:49+01:00",
              "end": "2026-11-18T21:45:59+01:00",
              "duration_seconds": 10,
              "triggers": ["person"],
              "hub_model": "Reolink Home Hub Mini",
              "hub_firmware": "v3.3.0.609_26092101",
              "uploader_version": "0.1.0"
            }
            """;

    @TempDir Path root;
    ClipFiles files;

    @BeforeEach
    void setUp() {
        CameraProperties props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(root.resolveSibling(root.getFileName() + "-thumbs"));
        files = new ClipFiles(props);
    }

    static void clip(Path root, String stem, String sidecar) throws Exception {
        Path mp4 = root.resolve(stem + ".mp4");
        Files.createDirectories(mp4.getParent());
        Files.write(mp4, new byte[]{0, 0, 0, 24});
        if (sidecar != null) Files.writeString(root.resolve(stem + ".json"), sidecar);
    }

    @Test
    void volumeOkRequiresMarkerText() throws Exception {
        assertThat(files.volumeOk()).isFalse();
        Files.writeString(root.resolve("volume-marker.txt"), "something else");
        assertThat(files.volumeOk()).isFalse();
        Files.writeString(root.resolve("volume-marker.txt"), MARKER_TEXT);
        assertThat(files.volumeOk()).isTrue();
    }

    @Test
    void listStemsMatchesLayoutOnly() throws Exception {
        clip(root, "Garage/2026-11-18/21-45-49", SIDECAR);
        clip(root, "Living Room/2026-11-18/21-45-49-2", SIDECAR);
        Files.writeString(root.resolve("volume-marker.txt"), MARKER_TEXT);
        Files.writeString(root.resolve("heartbeat.json"), "{}");
        Files.write(root.resolve("x.mp4"), new byte[]{1});
        clip(root, "Garage/notadate/1", null);
        Files.createFile(root.resolve("Garage/2026-11-18/21-45-49.keep"));
        assertThat(files.listStems()).containsExactlyInAnyOrder(
                "Garage/2026-11-18/21-45-49", "Living Room/2026-11-18/21-45-49-2");
    }

    @Test
    void readSidecarParsesUploaderJson() throws Exception {
        clip(root, "Garage/2026-11-18/21-45-49", SIDECAR);
        ClipSidecar s = files.readSidecar("Garage/2026-11-18/21-45-49").orElseThrow();
        assertThat(s.cameraName()).isEqualTo("Garage");
        assertThat(s.cameraId()).isEqualTo("9527000O0NWN1LLO");
        assertThat(s.channel()).isZero();
        assertThat(s.start()).isEqualTo(OffsetDateTime.parse("2026-11-18T21:45:49+01:00"));
        assertThat(s.start().getOffset().getTotalSeconds()).isEqualTo(3600);
        assertThat(s.end()).isEqualTo(OffsetDateTime.parse("2026-11-18T21:45:59+01:00"));
        assertThat(s.durationSeconds()).isEqualTo(10);
        assertThat(s.triggers()).isEqualTo(List.of("person"));
    }

    @Test
    void readSidecarEmptyWhenMissingOrTruncated() throws Exception {
        clip(root, "Garage/2026-11-18/21-45-49", "{\"camera_name\": \"Gar");
        assertThat(files.readSidecar("Garage/2026-11-18/21-45-49")).isEmpty();
        clip(root, "Garage/2026-11-18/21-46-00", null);
        assertThat(files.readSidecar("Garage/2026-11-18/21-46-00")).isEmpty();
    }

    @Test
    void setKeptCreatesAndRemovesMarker() throws Exception {
        clip(root, "Garage/2026-11-18/21-45-49", SIDECAR);
        assertThat(files.isKept("Garage/2026-11-18/21-45-49")).isFalse();
        files.setKept("Garage/2026-11-18/21-45-49", true);
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.keep")).exists();
        assertThat(files.isKept("Garage/2026-11-18/21-45-49")).isTrue();
        files.setKept("Garage/2026-11-18/21-45-49", false);
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.keep")).doesNotExist();
    }

    @Test
    void deleteClipRemovesMp4JsonKeep() throws Exception {
        clip(root, "Garage/2026-11-18/21-45-49", SIDECAR);
        files.setKept("Garage/2026-11-18/21-45-49", true);
        files.deleteClip("Garage/2026-11-18/21-45-49");
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.mp4")).doesNotExist();
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.json")).doesNotExist();
        assertThat(root.resolve("Garage/2026-11-18/21-45-49.keep")).doesNotExist();
    }

    @Test
    void deleteClipRejectsPathTraversalAndNonLayout() {
        assertThrows(IllegalArgumentException.class, () -> files.deleteClip("../x"));
        assertThrows(IllegalArgumentException.class, () -> files.deleteClip("Garage/../../etc/2026-11-18/21-45-49"));
        assertThrows(IllegalArgumentException.class, () -> files.deleteClip("volume-marker"));
        assertThrows(IllegalArgumentException.class, () -> files.mp4("../x"));
        assertThrows(IllegalArgumentException.class, () -> files.mp4("../2026-11-18/21-45-49"));
        assertThat(files.mp4("Porta.../2026-11-18/21-45-49").getFileName().toString()).isEqualTo("21-45-49.mp4");
    }

    @Test
    void readHeartbeat() throws Exception {
        assertThat(files.readHeartbeat()).isEmpty();
        Files.writeString(root.resolve("heartbeat.json"),
                "{\"time\":\"2026-11-18T21:50:00+01:00\",\"hub_ok\":true,\"hub_error_since\":null}");
        assertThat(files.readHeartbeat()).hasValueSatisfying(h -> assertThat(h.get("hub_ok").asBoolean()).isTrue());
    }
}
