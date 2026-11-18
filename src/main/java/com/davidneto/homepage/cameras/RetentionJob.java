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
        log.info("camera cleanup: {} clips older than {} days deleted, {} kept clips skipped",
                deleted, props.getRetentionDays(), skippedKept);
        return new RetentionResult(deleted, skippedKept, true, "ok");
    }
}
