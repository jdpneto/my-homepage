package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Daily cleanup: deletes clips whose recording started more than {@code retention-days} ago,
 * unless a {@code .keep} file exists next to them. The keep file is checked on disk for every
 * clip at deletion time, never only the cached database flag.
 * <p>
 * Safety net (after the daily cleanup and every 10 min): when the camera volume or the cameras'
 * WebDAV quota is more than {@code safety-net-high-percent} (85 %) used, the oldest non-kept clips
 * (by start time, whatever their age) are deleted until both are below
 * {@code safety-net-low-percent} (75 %). Kept clips never. Logged at WARN, announced by a push.
 * When kept clips and other data on the volume are so large that deleting every clip that is not
 * kept would not get below 75 %, it deletes only the oldest clips needed to get back under 85 %
 * (nothing at all when even that is out of reach) and pushes "disk full of kept clips or other
 * data" instead. Each of the two pushes goes out at most once per 24 h (the time is kept in
 * {@code camera_setting}, so a restart does not reset it); later runs only log.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public record RetentionResult(int deleted, int skippedKept, boolean ran, String reason) {
    }

    public record SafetyNetResult(boolean ran, int deleted, long freedBytes) {
    }

    static final String FREED_PUSH_KEY = "safety_net_freed_push_at";
    static final String FULL_PUSH_KEY = "safety_net_full_push_at";
    static final Duration PUSH_EVERY = Duration.ofHours(24);

    private final ClipFiles files;
    private final CameraClipRepository repo;
    private final ClipThumbnailer thumbnailer;
    private final ClipIndexer indexer;
    private final CameraProperties props;
    private final Clock clock;
    private final ClipService clips;
    private final Notifier notifier;
    private final AlertMute mute;
    private final CameraSettingRepository settings;
    private volatile String lastFailure;
    private volatile boolean ranSinceStart;

    public RetentionJob(ClipFiles files, CameraClipRepository repo, ClipThumbnailer thumbnailer, ClipIndexer indexer,
                        CameraProperties props, Clock clock, ClipService clips, Notifier notifier, AlertMute mute,
                        CameraSettingRepository settings) {
        this.files = files;
        this.repo = repo;
        this.thumbnailer = thumbnailer;
        this.indexer = indexer;
        this.props = props;
        this.clock = clock;
        this.clips = clips;
        this.notifier = notifier;
        this.mute = mute;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${app.cameras.safety-net-delay-ms:600000}",
            initialDelayString = "${app.cameras.safety-net-initial-delay-ms:600000}")
    public void scheduledSafetyNet() {
        try {
            safetyNet();
        } catch (RuntimeException e) {
            log.error("camera disk safety net failed", e);
        }
    }

    /**
     * Deletes the oldest non-kept clips while the volume or the quota is above the high mark: down
     * to the low mark, or only back under the high mark when the low mark is out of reach.
     */
    public synchronized SafetyNetResult safetyNet() {
        if (!files.volumeOk()) {
            log.warn("camera disk safety net skipped: volume missing");
            return new SafetyNetResult(false, 0, 0);
        }
        StorageStatus st = clips.status();
        long volumeUsed = st.volumeTotal() - st.volumeFree();
        int high = props.getSafetyNetHighPercent();
        int low = props.getSafetyNetLowPercent();
        if (!above(volumeUsed, st.volumeTotal(), high) && !above(st.quotaUsed(), st.quotaBytes(), high)) {
            return new SafetyNetResult(true, 0, 0);
        }

        // what may be deleted at all: kept on disk since the row was written counts as kept
        int skippedKept = 0;
        long deletableBytes = 0;
        List<CameraClip> deletable = new ArrayList<>();
        Map<Long, Long> sizes = new HashMap<>();
        for (CameraClip c : repo.findByKeptFalseOrderByStartAtAscIdAsc()) {
            if (files.isKept(c.getStem())) {
                skippedKept++;
                c.setKept(true);
                c.setKeptAt(clock.instant());
                repo.save(c);
                continue;
            }
            long size = mp4Size(c);
            sizes.put(c.getId(), size);
            deletableBytes += size;
            deletable.add(c);
        }
        long rest = deletableBytes;
        boolean lowReachable = !atOrAbove(volumeUsed - rest, st.volumeTotal(), low)
                && !atOrAbove(st.quotaUsed() - rest, st.quotaBytes(), low);
        boolean highReachable = !above(volumeUsed - rest, st.volumeTotal(), high)
                && !above(st.quotaUsed() - rest, st.quotaBytes(), high);

        long freed = 0;
        int deleted = 0;
        if (highReachable) {
            for (CameraClip c : deletable) {
                boolean done = lowReachable
                        ? !atOrAbove(volumeUsed - freed, st.volumeTotal(), low) && !atOrAbove(st.quotaUsed() - freed, st.quotaBytes(), low)
                        : !above(volumeUsed - freed, st.volumeTotal(), high) && !above(st.quotaUsed() - freed, st.quotaBytes(), high);
                if (done) break;
                if (files.isKept(c.getStem())) { // kept a moment ago: still never deleted
                    skippedKept++;
                    c.setKept(true);
                    c.setKeptAt(clock.instant());
                    repo.save(c);
                    continue;
                }
                files.deleteClip(c.getStem());
                thumbnailer.delete(c.getStem());
                repo.delete(c);
                freed += sizes.get(c.getId());
                deleted++;
            }
        }
        long volumePercent = percent(volumeUsed, st.volumeTotal());
        long quotaPercent = percent(st.quotaUsed(), st.quotaBytes());
        log.warn("camera disk safety net: volume {}% used, quota {}% used; deleted {} oldest clips ({} bytes), "
                        + "{} kept skipped{}", volumePercent, quotaPercent, deleted, freed, skippedKept,
                lowReachable ? "" : " - deleting every clip that is not kept would not get below " + low
                        + "%: the rest is kept clips or other data"
                        + (highReachable ? ", deleted only enough to get under " + high + "%" : ", nothing deleted"));
        if (!lowReachable) {
            pushAtMostDaily(FULL_PUSH_KEY, "Camera disk full of kept clips or other data",
                    "The camera disk is over " + high + "% used (volume " + volumePercent + "% used, quota "
                            + quotaPercent + "% used). Deleting every clip that is not kept would not get it below "
                            + low + "%"
                            + (deleted > 0 ? ": deleted " + deleted + " oldest " + (deleted == 1 ? "clip" : "clips")
                            + " (" + AdminCamerasController.size(freed) + ") to get under " + high + "%." : ": nothing was deleted.")
                            + " Unkeep clips or free space on the volume.");
        } else if (deleted > 0) {
            pushAtMostDaily(FREED_PUSH_KEY, "Disk safety net freed " + AdminCamerasController.size(freed) + " (" + deleted
                            + " oldest " + (deleted == 1 ? "clip" : "clips") + ")",
                    "The camera disk was over " + high + "% used: the oldest clips that were not kept were deleted.");
        }
        return new SafetyNetResult(true, deleted, freed);
    }

    /** Sends unless muted or this push already went out in the last 24 h; only a delivered push counts. */
    private void pushAtMostDaily(String key, String title, String message) {
        if (mute.get().muted()) return;
        Instant now = clock.instant();
        Instant last = settings.findById(key).map(CameraSetting::getValue).map(v -> {
            try {
                return Instant.parse(v);
            } catch (RuntimeException e) {
                return null;
            }
        }).orElse(null);
        if (last != null && now.isBefore(last.plus(PUSH_EVERY))) {
            log.info("camera disk safety net push skipped (one a day): {}", title);
            return;
        }
        if (notifier.send(title, message, "default")) settings.save(new CameraSetting(key, now.toString()));
    }

    private long mp4Size(CameraClip c) {
        try {
            return java.nio.file.Files.size(files.mp4(c.getStem()));
        } catch (java.io.IOException | RuntimeException e) {
            return 0; // already gone: frees nothing
        }
    }

    private static boolean above(long used, long total, int percent) {
        return total > 0 && used * 100 > (long) percent * total;
    }

    private static boolean atOrAbove(long used, long total, int percent) {
        return total > 0 && used * 100 >= (long) percent * total;
    }

    private static long percent(long used, long total) {
        return total <= 0 ? 0 : Math.round(used * 100.0 / total);
    }

    @Scheduled(cron = "${app.cameras.cleanup-cron:0 30 3 * * *}")
    public void scheduled() {
        try {
            run();
            safetyNet();
            lastFailure = null;
        } catch (RuntimeException e) {
            lastFailure = e.toString();
            log.error("camera cleanup failed", e);
        } finally {
            ranSinceStart = true;
        }
    }

    /** Error text of the most recent scheduled run if it failed; empty after a successful run. */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /** False until the scheduled cleanup has run in this process: its outcome is unknown until then. */
    public boolean hasRunSinceStart() {
        return ranSinceStart;
    }

    public RetentionResult run() {
        return run(true);
    }

    synchronized RetentionResult run(boolean reconcileFirst) {
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
