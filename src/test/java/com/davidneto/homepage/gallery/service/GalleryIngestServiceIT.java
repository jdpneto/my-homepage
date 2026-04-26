package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GalleryIngestServiceIT {

    @Autowired GalleryIngestService ingest;
    @Autowired GalleryItemRepository repo;
    @Autowired GalleryStorage storage;

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
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateExifDirectory()
                .add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, outputSet);
        return out.toByteArray();
    }

    @Test
    void photoIngest_createsRow_writesAllDerivatives_bucketsByExif() throws Exception {
        byte[] bytes = jpegWithExif("2010:06:15 12:30:00");

        GalleryIngestService.IngestResult r = ingest.ingest(
                new ByteArrayInputStream(bytes), "vacation.jpg", "image/jpeg", "Maria");

        assertThat(r.deduped()).isFalse();
        GalleryItem item = repo.findById(r.itemId()).orElseThrow();
        assertThat(item.getMediaKind()).isEqualTo(MediaKind.PHOTO);
        assertThat(item.getBucketYear()).isEqualTo(2010);
        assertThat(item.getBucketMonth()).isEqualTo(6);
        assertThat(item.getBucketSource()).isEqualTo("EXIF");
        assertThat(item.getUploaderName()).isEqualTo("Maria");
        assertThat(item.getContentHash()).hasSize(64);
        assertThat(Files.exists(storage.originalPath(item.getStorageKey(), "jpg"))).isTrue();
        assertThat(Files.exists(storage.thumbPath(item.getStorageKey()))).isTrue();
        assertThat(Files.exists(storage.displayPath(item.getStorageKey()))).isTrue();
    }

    @Test
    void photoIngest_withoutExif_bucketsByUploadTime_andSetsBucketSourceUpload() throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);

        var r = ingest.ingest(new ByteArrayInputStream(baos.toByteArray()),
                "phone.jpg", "image/jpeg", null);

        GalleryItem item = repo.findById(r.itemId()).orElseThrow();
        assertThat(item.getBucketSource()).isEqualTo("UPLOAD");
        assertThat(item.getTakenAt()).isNull();
        assertThat(item.getBucketYear()).isEqualTo(item.getUploadedAt().getYear());
        assertThat(item.getBucketMonth()).isEqualTo(item.getUploadedAt().getMonthValue());
    }

    @Test
    void duplicateIngest_isANoop_returnsDedupedTrue_andSameItemId() throws Exception {
        byte[] bytes = jpegWithExif("2018:01:01 08:00:00");

        var first = ingest.ingest(new ByteArrayInputStream(bytes), "a.jpg", "image/jpeg", "X");
        var second = ingest.ingest(new ByteArrayInputStream(bytes), "b.jpg", "image/jpeg", "Y");

        assertThat(first.deduped()).isFalse();
        assertThat(second.deduped()).isTrue();
        assertThat(second.itemId()).isEqualTo(first.itemId());
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void rejectsDisallowedMimeBySniffing() throws Exception {
        byte[] notAnImage = "this is plain text".getBytes();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ingest.ingest(new ByteArrayInputStream(notAnImage), "file.jpg", "image/jpeg", null)
        ).isInstanceOf(GalleryIngestService.UnsupportedMediaException.class);
    }

    @Test
    void rejectsPhotoExceedingFiftyMegabyteCap() throws Exception {
        // A real but oversized JPEG: tiny image padded with extra image data is impractical.
        // Fake the size by writing a 51 MB byte stream that starts with valid JPEG magic.
        byte[] head = jpegWithExif("2020:01:01 00:00:00");
        byte[] payload = new byte[51 * 1024 * 1024];
        System.arraycopy(head, 0, payload, 0, head.length);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ingest.ingest(new java.io.ByteArrayInputStream(payload), "huge.jpg", "image/jpeg", null)
        ).isInstanceOf(GalleryIngestService.UnsupportedMediaException.class)
         .hasMessageContaining("50 MB");
    }
}
