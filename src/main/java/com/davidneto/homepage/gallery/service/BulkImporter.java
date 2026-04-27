package com.davidneto.homepage.gallery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
@Profile("bulkimport")
public class BulkImporter implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BulkImporter.class);

    /** Sidecar manifest written by scripts/bulk-import.sh before rsync. */
    static final String MANIFEST_NAME = ".taken-at-manifest.tsv";

    private final GalleryIngestService ingest;

    @Value("${gallery.import.path:}")
    private String importPath;

    @Value("${gallery.import.uploader:}")
    private String uploader;

    public BulkImporter(GalleryIngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (importPath == null || importPath.isBlank()) {
            LOG.error("bulkimport profile active but --gallery.import.path is not set");
            return;
        }
        importDirectory(Path.of(importPath), uploader == null || uploader.isBlank() ? null : uploader);
    }

    public void importDirectory(Path root, String uploaderName) throws Exception {
        Map<Path, LocalDateTime> manifest = readManifest(root);
        AtomicLong scanned = new AtomicLong();
        AtomicLong ingested = new AtomicLong();
        AtomicLong deduped = new AtomicLong();
        AtomicLong rejected = new AtomicLong();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals(MANIFEST_NAME))
                .forEach(p -> {
                    long n = scanned.incrementAndGet();
                    LocalDateTime fallback = manifest.get(p.toAbsolutePath().normalize());
                    if (fallback == null) {
                        try {
                            fallback = LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(p).toInstant(),
                                    ZoneId.systemDefault());
                        } catch (Exception e) {
                            fallback = null;
                        }
                    }
                    try (InputStream in = Files.newInputStream(p)) {
                        var r = ingest.ingest(in, p.getFileName().toString(), null, uploaderName, fallback);
                        if (r.deduped()) deduped.incrementAndGet();
                        else ingested.incrementAndGet();
                    } catch (GalleryIngestService.UnsupportedMediaException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        LOG.warn("failed to ingest {}: {}", p, e.toString());
                        rejected.incrementAndGet();
                    }
                    if (n % 50 == 0) {
                        LOG.info("scanned={}, ingested={}, deduped={}, rejected={}",
                                n, ingested.get(), deduped.get(), rejected.get());
                    }
                });
        }

        LOG.info("bulk import complete: scanned={}, ingested={}, deduped={}, rejected={}",
                scanned.get(), ingested.get(), deduped.get(), rejected.get());
    }

    /**
     * Reads the optional birthtime manifest produced by scripts/bulk-import.sh.
     * Format: each line is "<relative-path>\t<unix-epoch-seconds>". Lines with
     * non-positive epoch or unparsable values are skipped. Returns an empty map
     * if the manifest is absent.
     */
    private Map<Path, LocalDateTime> readManifest(Path root) {
        Path manifestPath = root.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifestPath)) return Map.of();

        Map<Path, LocalDateTime> out = new HashMap<>();
        try (Stream<String> lines = Files.lines(manifestPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) return;
                String rel = line.substring(0, tab);
                String epochStr = line.substring(tab + 1).trim();
                long epoch;
                try { epoch = Long.parseLong(epochStr); }
                catch (NumberFormatException nfe) { return; }
                if (epoch <= 0) return;
                Path key = root.resolve(rel).toAbsolutePath().normalize();
                out.put(key, LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.systemDefault()));
            });
        } catch (IOException e) {
            LOG.warn("could not read manifest {}: {}", manifestPath, e.toString());
            return Map.of();
        }
        LOG.info("loaded {} entries from {}", out.size(), manifestPath);
        return out;
    }
}
