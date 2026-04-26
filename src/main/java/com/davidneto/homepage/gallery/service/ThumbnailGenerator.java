package com.davidneto.homepage.gallery.service;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ThumbnailGenerator {

    private static final int THUMB_MAX = 400;
    private static final int DISPLAY_MAX = 1600;
    private static final float JPEG_THUMB_QUALITY = 0.80f;
    private static final float JPEG_DISPLAY_QUALITY = 0.85f;

    public void writeThumbnail(Path src, Path out, Integer exifOrientation) throws IOException {
        write(src, out, THUMB_MAX, JPEG_THUMB_QUALITY, exifOrientation);
    }

    public void writeDisplay(Path src, Path out, Integer exifOrientation) throws IOException {
        write(src, out, DISPLAY_MAX, JPEG_DISPLAY_QUALITY, exifOrientation);
    }

    private void write(Path src, Path out, int maxLongSide, float quality, Integer orientation) throws IOException {
        Files.createDirectories(out.getParent());
        BufferedImage srcImage = ImageIO.read(src.toFile());
        int longestSide = Math.max(srcImage.getWidth(), srcImage.getHeight());
        int targetSize = Math.min(longestSide, maxLongSide);
        var builder = Thumbnails.of(src.toFile())
                .size(targetSize, targetSize)
                .keepAspectRatio(true)
                .outputFormat("jpg")
                .outputQuality(quality);
        if (orientation != null) {
            switch (orientation) {
                case 3 -> builder.rotate(180);
                case 6 -> builder.rotate(90);
                case 8 -> builder.rotate(270);
                default -> { /* 1 (or unknown) — no rotation */ }
            }
        }
        builder.toFile(out.toFile());
    }
}
