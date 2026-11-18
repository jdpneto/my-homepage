package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the {@code camera_clip} table in step with the volume. A clip counts only once its
 * {@code .json} exists and parses (the uploader writes it after the {@code .mp4}).
 * Does nothing at all while the volume marker is missing, so a detached volume never
 * makes rows (and their keep flags) disappear.
 */
@Component
public class ClipIndexer {

    private static final Logger log = LoggerFactory.getLogger(ClipIndexer.class);

    public record IndexResult(int added, int removed, int keptChanged, boolean volumeOk) {
    }

    private final ClipFiles files;
    private final ClipThumbnailer thumbnailer;
    private final CameraClipRepository repo;
    private final Clock clock;
    private Instant lastVolumeWarning = Instant.EPOCH;

    public ClipIndexer(ClipFiles files, ClipThumbnailer thumbnailer, CameraClipRepository repo, Clock clock) {
        this.files = files;
        this.thumbnailer = thumbnailer;
        this.repo = repo;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void scheduled() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.error("clip index reconcile failed", e);
        }
    }

    public synchronized IndexResult reconcile() {
        Instant now = clock.instant();
        if (!files.volumeOk()) {
            if (Duration.between(lastVolumeWarning, now).toHours() >= 1) {
                log.warn("camera volume marker missing under {} - not indexing", files.root());
                lastVolumeWarning = now;
            }
            return new IndexResult(0, 0, 0, false);
        }

        Set<String> onDisk = new HashSet<>(files.listStems());
        Map<String, CameraClip> rows = new HashMap<>();
        for (CameraClip c : repo.findAll()) rows.put(c.getStem(), c);

        int added = 0, removed = 0, keptChanged = 0;

        for (String stem : onDisk) {
            CameraClip row = rows.get(stem);
            if (row == null) {
                var sidecar = files.readSidecar(stem);
                if (sidecar.isEmpty()) continue; // not finished uploading yet
                repo.save(newRow(stem, sidecar.get(), now));
                added++;
            } else {
                boolean kept = files.isKept(stem);
                boolean dirty = false;
                if (row.isKept() != kept) {
                    row.setKept(kept);
                    row.setKeptAt(kept ? now : null);
                    keptChanged++;
                    dirty = true;
                }
                if (row.getThumbPath() == null) {
                    var thumb = thumbnailer.ensure(stem);
                    if (thumb.isPresent()) {
                        row.setThumbPath(thumb.get().toString());
                        dirty = true;
                    }
                }
                if (dirty) repo.save(row);
            }
        }

        for (CameraClip row : rows.values()) {
            if (!onDisk.contains(row.getStem())) {
                thumbnailer.delete(row.getStem());
                repo.delete(row);
                removed++;
            }
        }

        if (added + removed + keptChanged > 0) {
            log.info("clip index: {} added, {} removed, {} keep changes", added, removed, keptChanged);
        }
        return new IndexResult(added, removed, keptChanged, true);
    }

    private CameraClip newRow(String stem, ClipSidecar s, Instant now) {
        CameraClip c = new CameraClip();
        c.setStem(stem);
        c.setCameraName(s.cameraName());
        c.setCameraId(s.cameraId());
        c.setChannel(s.channel());
        c.setStartAt(s.start().toInstant());
        c.setUtcOffsetSeconds(s.start().getOffset().getTotalSeconds());
        c.setEndAt(s.end().toInstant());
        c.setDurationSeconds(s.durationSeconds());
        c.setTriggers(String.join(",", s.triggers().stream().map(String::toLowerCase).sorted().toList()));
        try {
            c.setSizeBytes(Files.size(files.mp4(stem)));
        } catch (IOException e) {
            c.setSizeBytes(0);
        }
        boolean kept = files.isKept(stem);
        c.setKept(kept);
        c.setKeptAt(kept ? now : null);
        c.setIndexedAt(now);
        thumbnailer.ensure(stem).ifPresent(p -> c.setThumbPath(p.toString()));
        return c;
    }
}
