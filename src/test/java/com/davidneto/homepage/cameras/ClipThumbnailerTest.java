package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClipThumbnailerTest {

    @TempDir Path tmp;
    Path root;
    CameraProperties props;
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        root = tmp.resolve("cameras");
        props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(tmp.resolve("thumbs"));
        ClipFilesTest.clip(root, "Garage/2026-11-18/21-45-49", ClipFilesTest.SIDECAR);
    }

    ClipThumbnailer thumbnailer(FrameExtractor extractor) {
        return new ClipThumbnailer(props, new ClipFiles(props), extractor);
    }

    @Test
    void ensureCreatesOnceAndReuses() {
        ClipThumbnailer t = thumbnailer((mp4, jpg) -> {
            calls.incrementAndGet();
            Files.write(jpg, new byte[]{(byte) 0xFF, (byte) 0xD8, 1});
        });
        Path first = t.ensure("Garage/2026-11-18/21-45-49").orElseThrow();
        Path second = t.ensure("Garage/2026-11-18/21-45-49").orElseThrow();
        assertThat(first).isEqualTo(second).exists();
        assertThat(first.startsWith(props.getThumbsDir())).isTrue();
        assertThat(first.startsWith(root)).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ensureEmptyOnExtractorFailure() {
        ClipThumbnailer t = thumbnailer((mp4, jpg) -> { throw new IOException("ffmpeg died"); });
        assertThat(t.ensure("Garage/2026-11-18/21-45-49")).isEmpty();
    }

    @Test
    void ensureEmptyWhenExtractorProducesNothing() {
        ClipThumbnailer t = thumbnailer((mp4, jpg) -> { });
        assertThat(t.ensure("Garage/2026-11-18/21-45-49")).isEmpty();
    }

    @Test
    void deleteRemovesFile() {
        ClipThumbnailer t = thumbnailer((mp4, jpg) -> Files.write(jpg, new byte[]{1}));
        Path p = t.ensure("Garage/2026-11-18/21-45-49").orElseThrow();
        t.delete("Garage/2026-11-18/21-45-49");
        assertThat(p).doesNotExist();
    }

    @Test
    void realFfmpegMakes480pxJpeg() throws Exception {
        assumeTrue(onPath("ffmpeg") && onPath("ffprobe"), "ffmpeg/ffprobe not on PATH");
        Path mp4 = root.resolve("Garage/2026-11-18/21-45-49.mp4");
        run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=15:duration=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", mp4.toString());
        Path jpg = thumbnailer(new FfmpegFrameExtractor()).ensure("Garage/2026-11-18/21-45-49").orElseThrow();
        byte[] head = Files.readAllBytes(jpg);
        assertThat(head[0]).isEqualTo((byte) 0xFF);
        assertThat(head[1]).isEqualTo((byte) 0xD8);
        String width = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width",
                "-of", "csv=p=0", jpg.toString()).trim();
        assertThat(width).isEqualTo("480");
    }

    @Test
    void realFfmpegHandlesSubSecondClip() throws Exception {
        assumeTrue(onPath("ffmpeg"), "ffmpeg not on PATH");
        Path mp4 = root.resolve("Garage/2026-11-18/21-45-49.mp4");
        run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=15:duration=0.5",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", mp4.toString());
        assertThat(thumbnailer(new FfmpegFrameExtractor()).ensure("Garage/2026-11-18/21-45-49")).isPresent();
    }

    static boolean onPath(String exe) {
        try {
            return new ProcessBuilder(exe, "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(out);
        return out;
    }
}
