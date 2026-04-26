package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoPosterGeneratorTest {

    @TempDir Path tmp;

    @Test
    void writesPosterByCallingFfmpegRunnerWithExpectedArgs() throws Exception {
        Path src = tmp.resolve("v.mp4");
        Files.write(src, new byte[]{0});
        Path out = tmp.resolve("poster.jpg");

        VideoPosterGenerator gen = new VideoPosterGenerator((args) -> {
            // First-frame extract (no -ss seek, more reliable for short clips).
            assertThat(args).contains("-frames:v", "1");
            assertThat(args).doesNotContain("-ss");
            assertThat(args).endsWith(out.toString());
            BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", out.toFile());
        });

        gen.writePoster(src, out);
        assertThat(Files.exists(out)).isTrue();
        BufferedImage img = ImageIO.read(out.toFile());
        assertThat(img.getWidth()).isEqualTo(640);
    }

    @Test
    void throwsWhenFfmpegExitsZeroButProducesNoOutput() throws Exception {
        Path src = tmp.resolve("v.mp4");
        Files.write(src, new byte[]{0});
        Path out = tmp.resolve("missing-poster.jpg");

        // Simulate ffmpeg exiting cleanly without writing the output (the
        // failure mode that produced ImageIO "Can't read input file!" before).
        VideoPosterGenerator gen = new VideoPosterGenerator((args) -> { /* no-op */ });

        assertThatThrownBy(() -> gen.writePoster(src, out))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("produced no poster output");
    }
}
