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
 * Every 5 minutes: is the uploader alive, is the hub reachable, is there space, did the cleanup
 * work? One push when a problem starts, one when it resolves, a reminder every 24 h while it
 * lasts. State is stored, so a restart does not re-send.
 */
@Component
public class AlertChecker {

    private static final Logger log = LoggerFactory.getLogger(AlertChecker.class);

    static final Map<String, String> TITLES = Map.of(
            "uploader_silent", "Camera uploader silent",
            "hub_down", "Camera hub unreachable",
            "disk_low", "Camera storage almost full",
            "volume_missing", "Camera volume missing",
            "cleanup_failed", "Camera cleanup failed");

    private final ClipService clips;
    private final ClipFiles files;
    private final RetentionJob retention;
    private final AlertStateRepository states;
    private final Notifier notifier;
    private final CameraProperties props;
    private final Clock clock;

    public AlertChecker(ClipService clips, ClipFiles files, RetentionJob retention, AlertStateRepository states,
                        Notifier notifier, CameraProperties props, Clock clock) {
        this.clips = clips;
        this.files = files;
        this.retention = retention;
        this.states = states;
        this.notifier = notifier;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduled() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("camera alert check failed", e);
        }
    }

    public synchronized void check() {
        Instant now = clock.instant();
        Map<String, Optional<String>> problems = new LinkedHashMap<>();

        Optional<JsonNode> hb = files.readHeartbeat();
        Instant hbAt = hb.flatMap(h -> instant(h.path("time"))).orElse(null);
        problems.put("uploader_silent", hbAt == null
                ? Optional.of("no heartbeat from the Mac mini uploader")
                : Duration.between(hbAt, now).compareTo(props.getHeartbeatMaxAge()) > 0
                        ? Optional.of("last heartbeat " + hb.get().path("time").asString())
                        : Optional.empty());

        Optional<String> hubDown = Optional.empty();
        if (hb.isPresent() && hb.get().has("hub_ok") && !hb.get().get("hub_ok").asBoolean()) {
            Optional<Instant> since = instant(hb.get().path("hub_error_since"));
            if (since.isPresent() && Duration.between(since.get(), now).compareTo(props.getHubDownMaxAge()) >= 0) {
                String err = hb.get().path("last_error").isNull() ? "" : " - " + hb.get().path("last_error").asString();
                hubDown = Optional.of("since " + hb.get().path("hub_error_since").asString() + err);
            }
        }
        problems.put("hub_down", hubDown);

        StorageStatus status = clips.status();
        problems.put("volume_missing", status.volumeOk() ? Optional.empty()
                : Optional.of("the 15 GB camera volume is not mounted on the VPS"));
        List<String> space = status.warnings().stream().filter(w -> !w.contains("volume missing")).toList();
        problems.put("disk_low", space.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", space)));

        problems.put("cleanup_failed", retention.lastFailure());

        problems.forEach((key, detail) -> update(key, detail, now));
    }

    private void update(String key, Optional<String> detail, Instant now) {
        AlertState s = states.findById(key).orElseGet(() -> {
            AlertState n = new AlertState();
            n.setAlertKey(key);
            n.setActive(false);
            return n;
        });
        String title = TITLES.get(key);
        if (detail.isPresent()) {
            boolean start = !s.isActive();
            boolean remind = s.isActive() && s.getLastNotifiedAt() != null
                    && Duration.between(s.getLastNotifiedAt(), now).compareTo(props.getReminderEvery()) >= 0;
            if (start || remind) {
                notifier.send(title, detail.get(), "high");
                s.setLastNotifiedAt(now);
                log.warn("camera alert {}: {}", start ? "started" : "reminder", title + " - " + detail.get());
            }
            if (start) s.setSince(now);
            s.setActive(true);
            s.setDetail(truncate(detail.get()));
            states.save(s);
        } else if (s.isActive()) {
            notifier.send("Resolved: " + title, "OK again", "default");
            log.info("camera alert resolved: {}", title);
            s.setActive(false);
            s.setSince(null);
            s.setDetail(null);
            s.setLastNotifiedAt(now);
            states.save(s);
        }
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
