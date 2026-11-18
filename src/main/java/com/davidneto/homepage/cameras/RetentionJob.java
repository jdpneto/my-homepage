package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Daily cleanup: deletes clips whose recording started more than {@code retention-days} ago,
 * unless a {@code .keep} file exists next to them. The keep file is checked on disk for every
 * clip at deletion time, never only the cached database flag.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public record RetentionResult(int deleted, int skippedKept, boolean ran, String reason) {
    }

    private final ClipFiles files;
    private final CameraClipRepository repo;
    private final ClipThumbnailer thumbnailer;
    private final ClipIndexer indexer;
    private final CameraProperties props;
    private final Clock clock;
    private volatile String lastFailure;

    public RetentionJob(ClipFiles files, CameraClipRepository repo, ClipThumbnailer thumbnailer, ClipIndexer indexer,
                        CameraProperties props, Clock clock) {
        this.files = files;
        this.repo = repo;
        this.thumbnailer = thumbnailer;
        this.indexer = indexer;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.cameras.cleanup-cron:0 30 3 * * *}")
    public void scheduled() {
        try {
            run();
            lastFailure = null;
        } catch (RuntimeException e) {
            lastFailure = e.toString();
            log.error("camera cleanup failed", e);
        }
    }

    /** Error text of the most recent scheduled run if it failed; empty after a successful run. */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    public RetentionResult run() {
        return run(true);
    }

    RetentionResult run(boolean reconcileFirst) {
        if (reconcileFirst) indexer.reconcile();
        if (!files.volumeOk()) {
            log.warn("camera cleanup skipped: volume missing");
            return new RetentionResult(0, 0, false, "volume missing");
        }
        if (repo.count() == 0 && !files.listStems().isEmpty()) {
            log.warn("camera cleanup skipped: index is empty but clips exist on the volume");
            return new RetentionResult(0, 0, false, "index empty");
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(props.getRetentionDays()));
        int deleted = 0, skippedKept = 0;
        for (CameraClip c : repo.findByStartAtBefore(cutoff)) {
            // Old by the hub's clock is not enough: a hub that booted with a wrong date stamps new
            // clips as old. Also require that the file arrived here before the cutoff.
            boolean arrivedBeforeCutoff = files.arrivedAt(c.getStem()).map(t -> t.isBefore(cutoff)).orElse(false);
            if (!arrivedBeforeCutoff) continue;
            if (files.isKept(c.getStem())) {
                skippedKept++;
                if (!c.isKept()) {
                    c.setKept(true);
                    c.setKeptAt(clock.instant());
                    repo.save(c);
                }
                continue;
            }
            files.deleteClip(c.getStem());
            thumbnailer.delete(c.getStem());
            repo.delete(c);
            deleted++;
        }
        // Clips whose .json never arrived are never indexed: remove them by arrival time alone.
        java.util.Set<String> indexed = new java.util.HashSet<>();
        repo.findAll().forEach(c -> indexed.add(c.getStem()));
        int orphans = 0;
        for (String stem : files.listStems()) {
            if (indexed.contains(stem) || files.isKept(stem)) continue;
            if (files.arrivedAt(stem).map(t -> t.isBefore(cutoff)).orElse(false)) {
                files.deleteClip(stem);
                thumbnailer.delete(stem);
                orphans++;
            }
        }
        log.info("camera cleanup: {} clips older than {} days deleted ({} without .json), {} kept clips skipped",
                deleted + orphans, props.getRetentionDays(), orphans, skippedKept);
        return new RetentionResult(deleted + orphans, skippedKept, true, "ok");
    }
}
