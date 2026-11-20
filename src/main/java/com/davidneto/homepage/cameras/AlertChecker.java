package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every 5 minutes: is the uploader alive and working, is the hub reachable with a sane clock, is
 * there space, did the cleanup work? One push when a problem starts, one when it resolves, a
 * reminder every 24 h while it lasts. A push only counts once ntfy accepted it (failed pushes are
 * retried at the next check). State is stored, so a restart does not re-send.
 */
@Component
public class AlertChecker {

    private static final Logger log = LoggerFactory.getLogger(AlertChecker.class);
    static final long HUB_SKEW_LIMIT_SECONDS = 3600;

    static final Map<String, String> TITLES = Map.of(
            "uploader_silent", "Camera uploader silent",
            "uploader_failing", "Camera uploader failing",
            "hub_down", "Camera hub unreachable",
            "hub_clock", "Camera hub clock wrong",
            "volume_missing", "Camera volume missing",
            "disk_volume", "Camera volume almost full",
            "disk_quota", "Camera quota almost used",
            "disk_root", "VPS disk almost full",
            "cleanup_failed", "Camera cleanup failed");

    private final ClipService clips;
    private final ClipFiles files;
    private final RetentionJob retention;
    private final AlertStateRepository states;
    private final Notifier notifier;
    private final CameraProperties props;
    private final Clock clock;
    private final AlertMute mute;
    private final Instant startedAt;

    public AlertChecker(ClipService clips, ClipFiles files, RetentionJob retention, AlertStateRepository states,
                        Notifier notifier, CameraProperties props, Clock clock, AlertMute mute) {
        this.clips = clips;
        this.files = files;
        this.retention = retention;
        this.states = states;
        this.notifier = notifier;
        this.props = props;
        this.clock = clock;
        this.mute = mute;
        this.startedAt = clock.instant();
    }

    @Scheduled(fixedDelayString = "${app.cameras.alert-delay-ms:300000}", initialDelayString = "${app.cameras.alert-initial-delay-ms:60000}")
    public void scheduled() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("camera alert check failed", e);
        }
    }

    public synchronized void check() {
        Instant now = clock.instant();
        // null value = "unknown right now": the stored state is left untouched
        Map<String, Optional<String>> problems = new LinkedHashMap<>();
        boolean inStartupGrace = Duration.between(startedAt, now).compareTo(props.getHeartbeatMaxAge()) < 0;

        Optional<JsonNode> hb = files.readHeartbeat();
        Instant hbAt = hb.flatMap(h -> instant(h.path("time"))).orElse(null);
        boolean hbFresh = hbAt != null && Duration.between(hbAt, now).compareTo(props.getHeartbeatMaxAge()) <= 0;
        if (hbFresh) {
            problems.put("uploader_silent", Optional.empty());
        } else {
            problems.put("uploader_silent", inStartupGrace ? null : Optional.of(hbAt == null
                    ? "no heartbeat from the Mac mini uploader"
                    : "last heartbeat " + hb.get().path("time").asString()));
        }

        // Alive but not uploading: last fully successful cycle too long ago (older uploaders send no last_ok).
        Optional<String> failing = Optional.empty();
        if (hbFresh) {
            Optional<Instant> lastOk = instant(hb.get().path("last_ok")); // absent/null = unknown
            if (lastOk.isPresent() && Duration.between(lastOk.get(), now).compareTo(props.getHeartbeatMaxAge()) > 0) {
                failing = Optional.of("no successful cycle since " + hb.get().path("last_ok").asString()
                        + lastError(hb.get()));
            }
        }
        problems.put("uploader_failing", hbFresh ? failing : null);

        Optional<String> hubDown = Optional.empty();
        if (hbFresh) {
            JsonNode hubOk = hb.get().get("hub_ok");
            if (hubOk != null && hubOk.isBoolean() && !hubOk.asBoolean()) {
                Optional<Instant> since = instant(hb.get().path("hub_error_since"));
                if (since.isPresent() && Duration.between(since.get(), now).compareTo(props.getHubDownMaxAge()) >= 0) {
                    hubDown = Optional.of("since " + hb.get().path("hub_error_since").asString() + lastError(hb.get()));
                }
            }
        }
        problems.put("hub_down", hbFresh ? hubDown : null);

        Optional<String> hubClock = Optional.empty();
        if (hbFresh && hb.get().path("hub_clock_skew_seconds").isNumber()) {
            long skew = hb.get().path("hub_clock_skew_seconds").asLong();
            if (Math.abs(skew) > HUB_SKEW_LIMIT_SECONDS) {
                hubClock = Optional.of("the hub's clock is off by " + Duration.ofSeconds(Math.abs(skew)).toHours()
                        + " h - check its date/time settings (clips get wrong dates)");
            }
        }
        problems.put("hub_clock", hbFresh ? hubClock : null);

        StorageStatus status = clips.status();
        problems.put("volume_missing", status.volumeOk() ? Optional.empty()
                : Optional.of("the 15 GB camera volume is not mounted on the VPS"));
        problems.put("disk_volume", warning(status.warnings(), ClipService.WARN_VOLUME));
        problems.put("disk_quota", warning(status.warnings(), ClipService.WARN_QUOTA));
        problems.put("disk_root", warning(status.warnings(), ClipService.WARN_ROOT));

        problems.put("cleanup_failed", retention.hasRunSinceStart() ? retention.lastFailure() : null);

        boolean muted = mute.get().muted();
        problems.forEach((key, detail) -> {
            if (detail != null) update(key, detail, now, muted);
        });
    }

    private void update(String key, Optional<String> detail, Instant now, boolean muted) {
        AlertState s = states.findById(key).orElseGet(() -> {
            AlertState n = new AlertState();
            n.setAlertKey(key);
            n.setActive(false);
            return n;
        });
        String title = TITLES.get(key);
        if (detail.isPresent()) {
            boolean start = !s.isActive();
            if (start) s.setSince(now);
            boolean remind = s.isActive() && s.getLastNotifiedAt() != null
                    && Duration.between(s.getLastNotifiedAt(), now).compareTo(props.getReminderEvery()) >= 0;
            if (start) s.setLastNotifiedAt(null);
            // Muted: keep tracking but send nothing; an undelivered start is announced after unmuting.
            if (!muted && (start || remind || s.getLastNotifiedAt() == null)) {
                if (notifier.send(title, detail.get(), "high")) {
                    s.setLastNotifiedAt(now);
                    log.warn("camera alert {}: {} - {}", start ? "started" : "sent", title, detail.get());
                } else {
                    log.warn("camera alert not delivered, will retry: {} - {}", title, detail.get());
                }
            }
            s.setActive(true);
            s.setDetail(truncate(detail.get()));
            states.save(s);
        } else if (s.isActive() && s.getLastNotifiedAt() == null) {
            // Never announced (e.g. it started and cleared while muted): close silently.
            s.setActive(false);
            s.setSince(null);
            s.setDetail(null);
            states.save(s);
        } else if (s.isActive() && !muted) {
            if (notifier.send("Resolved: " + title, "OK again", "default")) {
                log.info("camera alert resolved: {}", title);
                s.setActive(false);
                s.setSince(null);
                s.setDetail(null);
                s.setLastNotifiedAt(now);
                states.save(s);
            } else {
                log.warn("camera alert resolve not delivered, will retry: {}", title);
            }
        }
    }

    private static Optional<String> warning(List<String> warnings, String prefix) {
        return warnings.stream()
                .filter(w -> w.startsWith(prefix) && !w.equals(ClipService.WARN_VOLUME_MISSING))
                .findFirst();
    }

    private static String lastError(JsonNode hb) {
        JsonNode e = hb.path("last_error");
        return e.isMissingNode() || e.isNull() ? "" : " - " + e.asString();
    }

    private static Optional<Instant> instant(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return Optional.empty();
        try {
            return Optional.of(OffsetDateTime.parse(n.asString()).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
