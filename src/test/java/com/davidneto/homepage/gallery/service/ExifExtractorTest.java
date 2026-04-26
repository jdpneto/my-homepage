package com.davidneto.homepage.gallery.service;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExifExtractorTest {

    @TempDir Path tmp;
    ExifExtractor extractor = new ExifExtractor();

    private byte[] tinyJpeg() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] withExifDate(byte[] jpeg, String exifDate) throws Exception {
        TiffOutputSet outputSet = new TiffOutputSet();
        var exifDir = outputSet.getOrCreateExifDirectory();
        exifDir.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(jpeg, out, outputSet);
        return out.toByteArray();
    }

    @Test
    void readsDateTimeOriginalFromExif() throws Exception {
        byte[] jpeg = withExifDate(tinyJpeg(), "2010:06:15 12:30:00");
        Path file = tmp.resolve("with-exif.jpg");
        Files.write(file, jpeg);

        ExifExtractor.Result r = extractor.extract(file);
        assertThat(r.takenAt()).isEqualTo(LocalDateTime.of(2010, 6, 15, 12, 30, 0));
        assertThat(r.width()).isEqualTo(8);
        assertThat(r.height()).isEqualTo(8);
    }

    @Test
    void returnsEmptyTakenAtForJpegWithoutExif() throws Exception {
        byte[] jpeg = tinyJpeg();
        Path file = tmp.resolve("no-exif.jpg");
        Files.write(file, jpeg);

        ExifExtractor.Result r = extractor.extract(file);
        assertThat(r.takenAt()).isNull();
        assertThat(r.width()).isEqualTo(8);
        assertThat(r.height()).isEqualTo(8);
    }
}
