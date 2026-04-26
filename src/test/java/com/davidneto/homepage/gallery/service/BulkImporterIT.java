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
class BulkImporterIT {

    @Autowired GalleryItemRepository repo;
    @Autowired GalleryStorage storage;
    @Autowired GalleryIngestService ingest;

    @BeforeEach
    void wipe() throws Exception {
        repo.deleteAll();
        if (Files.exists(storage.root())) {
            try (var s = Files.walk(storage.root())) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        storage.init();
    }

    private void writeJpegWithExif(Path file, String exifDate) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        Files.createDirectories(file.getParent());
        Files.write(file, out.toByteArray());
    }

    @Test
    void importDirectory_walksTreeAndIngestsAllSupportedFiles() throws Exception {
        Path src = Files.createTempDirectory("bulk-src-");
        writeJpegWithExif(src.resolve("a.jpg"), "2010:01:01 00:00:00");
        writeJpegWithExif(src.resolve("sub/b.jpg"), "2011:02:02 00:00:00");
        // unsupported file:
        Files.writeString(src.resolve("readme.txt"), "ignore me");

        BulkImporter importer = new BulkImporter(ingest);
        importer.importDirectory(src, "David");

        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findAll().stream().map(g -> g.getUploaderName())).containsOnly("David");
    }

    @Test
    void importDirectory_isIdempotent_dedupedOnSecondRun() throws Exception {
        Path src = Files.createTempDirectory("bulk-src-");
        writeJpegWithExif(src.resolve("a.jpg"), "2012:03:03 00:00:00");

        BulkImporter importer = new BulkImporter(ingest);
        importer.importDirectory(src, null);
        importer.importDirectory(src, null);

        assertThat(repo.count()).isEqualTo(1);
    }
}
