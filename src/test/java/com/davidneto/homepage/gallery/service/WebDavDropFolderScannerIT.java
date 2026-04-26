package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WebDavDropFolderScannerIT {

    @Autowired GalleryStorage storage;
    @Autowired GalleryItemRepository repo;
    @Autowired WebDavDropFolderScanner scanner;

    @BeforeEach
    void wipe() throws Exception {
        repo.deleteAll();
        // Best-effort cleanup of test-gallery dir
        if (Files.exists(storage.root())) {
            try (var s = Files.walk(storage.root())) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        storage.init();
    }

    private byte[] jpegWithExif(String exifDate) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        return out.toByteArray();
    }

    @Test
    void stableFile_isIngestedAndDeletedFromDrop() throws Exception {
        Path dropFile = storage.dropDir().resolve("photo.jpg");
        Files.write(dropFile, jpegWithExif("2015:08:08 10:00:00"));
        long writeTime = Files.getLastModifiedTime(dropFile).toMillis();

        // Force the file's mtime back so "stable for N seconds" check passes immediately.
        Files.setLastModifiedTime(dropFile, java.nio.file.attribute.FileTime.fromMillis(writeTime - 60_000));

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isFalse();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void freshlyWrittenFile_isSkippedUntilStable() throws Exception {
        Path dropFile = storage.dropDir().resolve("fresh.jpg");
        Files.write(dropFile, jpegWithExif("2016:01:01 00:00:00"));
        // Leave mtime as "now" — should NOT be picked up.

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isTrue();
        assertThat(repo.count()).isZero();
    }

    @Test
    void unsupportedFile_movedToFailedWithErrorSidecar() throws Exception {
        Path dropFile = storage.dropDir().resolve("bad.txt");
        Files.writeString(dropFile, "not an image");
        Files.setLastModifiedTime(dropFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isFalse();
        assertThat(Files.exists(storage.failedDir().resolve("bad.txt"))).isTrue();
        assertThat(Files.exists(storage.failedDir().resolve("bad.txt.error"))).isTrue();
    }
}
