package com.davidneto.homepage.gallery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
@Profile("bulkimport")
public class BulkImporter implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BulkImporter.class);

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
        AtomicLong scanned = new AtomicLong();
        AtomicLong ingested = new AtomicLong();
        AtomicLong deduped = new AtomicLong();
        AtomicLong rejected = new AtomicLong();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                long n = scanned.incrementAndGet();
                try (InputStream in = Files.newInputStream(p)) {
                    var r = ingest.ingest(in, p.getFileName().toString(), null, uploaderName);
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
}
