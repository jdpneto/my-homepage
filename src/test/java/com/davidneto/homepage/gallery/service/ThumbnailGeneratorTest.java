package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailGeneratorTest {

    @TempDir Path tmp;
    ThumbnailGenerator gen = new ThumbnailGenerator();

    private Path writeJpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Path f = tmp.resolve("src-" + w + "x" + h + ".jpg");
        ImageIO.write(img, "jpg", f.toFile());
        return f;
    }

    @Test
    void thumb400_writesOutputJpegWithLongestSide400() throws Exception {
        Path src = writeJpeg(2000, 1000);
        Path out = tmp.resolve("out-thumb.jpg");
        gen.writeThumbnail(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(400);
    }

    @Test
    void display1600_writesOutputJpegWithLongestSide1600() throws Exception {
        Path src = writeJpeg(3200, 1600);
        Path out = tmp.resolve("out-display.jpg");
        gen.writeDisplay(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(1600);
    }

    @Test
    void doesNotUpscale_smallSourceCopiedNearAsIs() throws Exception {
        Path src = writeJpeg(100, 80);
        Path out = tmp.resolve("out-small.jpg");
        gen.writeThumbnail(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(80);
    }
}
