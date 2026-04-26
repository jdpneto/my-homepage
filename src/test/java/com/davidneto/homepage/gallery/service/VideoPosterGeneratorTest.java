package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VideoPosterGeneratorTest {

    @TempDir Path tmp;

    @Test
    void writesPosterByCallingFfmpegRunnerWithExpectedArgs() throws Exception {
        Path src = tmp.resolve("v.mp4");
        Files.write(src, new byte[]{0});
        Path out = tmp.resolve("poster.jpg");

        VideoPosterGenerator gen = new VideoPosterGenerator((args) -> {
            assertThat(args).contains("-ss", "00:00:01");
            assertThat(args).contains("-vframes", "1");
            assertThat(args).endsWith(out.toString());
            BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", out.toFile());
        });

        gen.writePoster(src, out);
        assertThat(Files.exists(out)).isTrue();
        BufferedImage img = ImageIO.read(out.toFile());
        assertThat(img.getWidth()).isEqualTo(640);
    }
}
