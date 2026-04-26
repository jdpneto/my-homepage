package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class GalleryIngestService {

    public record IngestResult(long itemId, boolean deduped) {}

    public static class UnsupportedMediaException extends RuntimeException {
        public UnsupportedMediaException(String msg) { super(msg); }
    }

    private final GalleryStorage storage;
    private final GalleryItemRepository repo;
    private final ExifExtractor exif;
    private final VideoMetadataExtractor videoMeta;
    private final ThumbnailGenerator thumbs;
    private final VideoPosterGenerator posters;

    public GalleryIngestService(GalleryStorage storage,
                                GalleryItemRepository repo,
                                ExifExtractor exif,
                                VideoMetadataExtractor videoMeta,
                                ThumbnailGenerator thumbs,
                                VideoPosterGenerator posters) {
        this.storage = storage;
        this.repo = repo;
        this.exif = exif;
        this.videoMeta = videoMeta;
        this.thumbs = thumbs;
        this.posters = posters;
    }

    @Transactional
    public IngestResult ingest(InputStream src, String originalFilename,
                               String declaredContentType, String uploaderName) throws IOException {
        Path tmp = storage.newTempFile();
        storage.ensureParentDirs(tmp);

        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }

        long size = 0;
        try (InputStream in = src; OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
                size += n;
            }
        }
        String hash = HexFormat.of().formatHex(sha.digest());

        var existing = repo.findByContentHash(hash);
        if (existing.isPresent()) {
            Files.deleteIfExists(tmp);
            return new IngestResult(existing.get().getId(), true);
        }

        String sniffed = MediaTypeSniffer.sniff(tmp);
        if (sniffed == null) {
            Files.deleteIfExists(tmp);
            throw new UnsupportedMediaException("unsupported or unrecognized media type for " + originalFilename);
        }
        MediaKind kind = sniffed.startsWith("video/") ? MediaKind.VIDEO : MediaKind.PHOTO;
        String ext = MediaTypeSniffer.extensionFor(sniffed);

        UUID storageKey = UUID.randomUUID();
        LocalDateTime uploadedAt = LocalDateTime.now();

        Integer width = null, height = null, durationSeconds = null, orientation = null;
        LocalDateTime takenAt = null;

        if (kind == MediaKind.PHOTO) {
            ExifExtractor.Result r = exif.extract(tmp);
            takenAt = r.takenAt();
            width = r.width();
            height = r.height();
            orientation = r.orientation();
        } else {
            VideoMetadataExtractor.Result r = videoMeta.extract(tmp);
            takenAt = r.takenAt();
            width = r.width();
            height = r.height();
            durationSeconds = r.durationSeconds();
        }

        int bucketYear, bucketMonth;
        String bucketSource;
        if (takenAt != null) {
            bucketYear = takenAt.getYear();
            bucketMonth = takenAt.getMonthValue();
            bucketSource = "EXIF";
        } else {
            bucketYear = uploadedAt.getYear();
            bucketMonth = uploadedAt.getMonthValue();
            bucketSource = "UPLOAD";
        }

        Path original = storage.originalPath(storageKey, ext);
        Path thumb = storage.thumbPath(storageKey);
        Path display = storage.displayPath(storageKey);
        storage.ensureParentDirs(original);
        storage.ensureParentDirs(thumb);
        storage.ensureParentDirs(display);

        try {
            Files.move(tmp, original, StandardCopyOption.ATOMIC_MOVE);

            if (kind == MediaKind.PHOTO) {
                thumbs.writeDisplay(original, display, orientation);
                thumbs.writeThumbnail(original, thumb, orientation);
            } else {
                posters.writePoster(original, display);
                thumbs.writeThumbnail(display, thumb, null);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(original); } catch (Exception ignored) {}
            try { Files.deleteIfExists(thumb); } catch (Exception ignored) {}
            try { Files.deleteIfExists(display); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw new IOException("ingest derivative generation failed", e);
        }

        GalleryItem item = new GalleryItem();
        item.setMediaKind(kind);
        item.setStorageKey(storageKey);
        item.setOriginalFilename(originalFilename);
        item.setContentType(sniffed);
        item.setSizeBytes(size);
        item.setContentHash(hash);
        item.setWidth(width);
        item.setHeight(height);
        item.setDurationSeconds(durationSeconds);
        item.setTakenAt(takenAt);
        item.setBucketYear(bucketYear);
        item.setBucketMonth(bucketMonth);
        item.setBucketSource(bucketSource);
        item.setUploadedAt(uploadedAt);
        item.setUploaderName(uploaderName);
        repo.save(item);
        return new IngestResult(item.getId(), false);
    }
}
