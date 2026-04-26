package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

@Component
public class WebDavDropFolderScanner {

    private static final Logger LOG = LoggerFactory.getLogger(WebDavDropFolderScanner.class);

    private final GalleryStorage storage;
    private final GalleryProperties props;
    private final GalleryIngestService ingest;

    public WebDavDropFolderScanner(GalleryStorage storage, GalleryProperties props, GalleryIngestService ingest) {
        this.storage = storage;
        this.props = props;
        this.ingest = ingest;
    }

    @Scheduled(fixedDelayString = "${app.gallery.drop.scan-interval-seconds:30}000")
    public void scheduledScan() {
        try { scanOnce(); }
        catch (Exception e) { LOG.error("drop scan failed", e); }
    }

    public void scanOnce() throws IOException {
        Path drop = storage.dropDir();
        if (!Files.isDirectory(drop)) return;

        long stableCutoff = System.currentTimeMillis() - (props.getDrop().getStableAfterSeconds() * 1000L);

        try (Stream<Path> walk = Files.walk(drop)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(storage.failedDir()))
                .forEach(p -> handle(p, stableCutoff));
        }
    }

    private void handle(Path file, long stableCutoff) {
        try {
            FileTime mtime = Files.getLastModifiedTime(file);
            if (mtime.toMillis() > stableCutoff) return; // not stable yet

            try (InputStream in = Files.newInputStream(file)) {
                ingest.ingest(in, file.getFileName().toString(), null, null);
            }
            Files.deleteIfExists(file);
        } catch (GalleryIngestService.UnsupportedMediaException e) {
            moveToFailed(file, e.getMessage());
        } catch (Exception e) {
            LOG.warn("ingest failed for {}: {}", file, e.toString());
            moveToFailed(file, e.toString());
        }
    }

    private void moveToFailed(Path file, String reason) {
        try {
            Path target = storage.failedDir().resolve(file.getFileName());
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(storage.failedDir().resolve(file.getFileName().toString() + ".error"),
                    reason == null ? "" : reason);
        } catch (Exception ex) {
            LOG.error("could not move {} to failed dir", file, ex);
        }
    }
}
