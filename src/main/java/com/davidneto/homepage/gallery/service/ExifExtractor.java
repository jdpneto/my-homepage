package com.davidneto.homepage.gallery.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
public class ExifExtractor {

    public record Result(LocalDateTime takenAt, Integer width, Integer height, Integer orientation) {}

    public Result extract(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return extract(in);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }

    public Result extract(InputStream in) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(in);

            LocalDateTime taken = null;
            ExifSubIFDDirectory sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                Date d = sub.getDateOriginal(java.util.TimeZone.getDefault());
                if (d != null) {
                    taken = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                }
            }

            Integer width = null, height = null;
            JpegDirectory jpeg = md.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpeg != null) {
                if (jpeg.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) width = jpeg.getInt(JpegDirectory.TAG_IMAGE_WIDTH);
                if (jpeg.containsTag(JpegDirectory.TAG_IMAGE_HEIGHT)) height = jpeg.getInt(JpegDirectory.TAG_IMAGE_HEIGHT);
            }

            Integer orientation = null;
            ExifIFD0Directory ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }

            return new Result(taken, width, height, orientation);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }
}
