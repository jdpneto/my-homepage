package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A push when a person is recorded while the hub is in away mode: the clip's triggers contain
 * {@code person}, its sidecar mode is {@code away} and it started at most 30 min before it was
 * indexed (so a backlog or re-index never floods). At most one push per hour: clips inside the
 * hour are counted per camera and sent as one summary when the hour ends, which starts the next
 * hour. While alerts are muted nothing is sent and the counts are dropped. A push only counts once
 * ntfy accepted it; undelivered ones stay pending for the next flush. State lives in one
 * {@code camera_setting} row so a restart neither re-pushes nor loses a pending summary.
 * Never throws into the indexer or the scheduler.
 */
@Component
public class PersonPushNotifier implements ClipIndexer.NewClipListener {

    private static final Logger log = LoggerFactory.getLogger(PersonPushNotifier.class);

    static final String STATE_KEY = "person_push_state";
    static final Duration MAX_CLIP_AGE = Duration.ofMinutes(30);
    static final Duration EVERY = Duration.ofHours(1);
    static final String PRIORITY = "high";
    static final String TAGS = "bust_in_silhouette";
    /** camera_setting.value is VARCHAR(500). */
    static final int MAX_STATE_LENGTH = 500;
    static final String OTHER = "other cameras";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final Notifier notifier;
    private final CameraSettingRepository settings;
    private final AlertMute mute;
    private final CameraProperties props;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();

    public PersonPushNotifier(Notifier notifier, CameraSettingRepository settings, AlertMute mute,
                              CameraProperties props, Clock clock) {
        this.notifier = notifier;
        this.settings = settings;
        this.mute = mute;
        this.props = props;
        this.clock = clock;
    }

    /** Last delivered push (single or summary) and the clips counted since, per camera. */
    private record State(Instant lastPush, Map<String, Integer> pending) {
        int total() {
            return pending.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    @Override
    public synchronized void clipAdded(ClipSidecar s, Instant indexedAt) {
        try {
            if (!qualifies(s, indexedAt)) return;
            Instant now = clock.instant();
            State state = load();
            if (mute.get().muted()) {
                if (!state.pending().isEmpty()) save(new State(state.lastPush(), new LinkedHashMap<>()));
                return;
            }
            boolean hourOver = hourIsOver(state, now);
            boolean attempted = false;
            if (hourOver && state.pending().isEmpty()) {
                if (notifier.send(title(s), "Away mode", PRIORITY, TAGS, cameraUrl(s.cameraName()))) {
                    log.info("person push sent: {}", s.cameraName());
                    save(new State(now, state.pending()));
                    return;
                }
                attempted = true;
                log.warn("person push not delivered, kept for the next summary: {}", s.cameraName());
            }
            Map<String, Integer> pending = new LinkedHashMap<>(state.pending());
            pending.merge(s.cameraName(), 1, Integer::sum);
            State counted = new State(state.lastPush(), pending);
            save(counted);
            // the hour already ended with clips pending but the minute job has not run yet: send them together now
            // (after a failed single push, the retry is left to the next flush)
            if (hourOver && !attempted) sendSummary(counted, now);
        } catch (RuntimeException e) {
            log.warn("person push failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${app.cameras.person-push-delay-ms:60000}",
            initialDelayString = "${app.cameras.person-push-initial-delay-ms:60000}")
    public void scheduled() {
        flush();
    }

    /** Sends the pending summary once the hour since the last push is over. */
    public synchronized void flush() {
        try {
            State state = load();
            if (state.pending().isEmpty()) return;
            if (mute.get().muted()) {
                save(new State(state.lastPush(), new LinkedHashMap<>()));
                return;
            }
            Instant now = clock.instant();
            if (hourIsOver(state, now)) sendSummary(state, now);
        } catch (RuntimeException e) {
            log.warn("person push summary failed: {}", e.getClass().getSimpleName());
        }
    }

    private void sendSummary(State state, Instant now) {
        int n = state.total();
        String title = n + " more person " + (n == 1 ? "clip" : "clips") + " while away";
        String body = state.pending().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        if (notifier.send(title, body, PRIORITY, TAGS, base() + "/admin/cameras?trigger=person")) {
            log.info("person push summary sent: {}", title);
            save(new State(now, new LinkedHashMap<>()));
        } else {
            log.warn("person push summary not delivered, will retry: {}", title);
        }
    }

    private static boolean qualifies(ClipSidecar s, Instant indexedAt) {
        if (s == null || s.start() == null || s.triggers() == null || s.cameraName() == null) return false;
        boolean person = s.triggers().stream().anyMatch(t -> t != null && t.equalsIgnoreCase("person"));
        boolean away = s.mode() != null && s.mode().trim().equalsIgnoreCase("away");
        boolean recent = !s.start().toInstant().isBefore(indexedAt.minus(MAX_CLIP_AGE));
        return person && away && recent;
    }

    private static boolean hourIsOver(State state, Instant now) {
        return state.lastPush() == null || !now.isBefore(state.lastPush().plus(EVERY));
    }

    private static String title(ClipSidecar s) {
        return "Person · " + s.cameraName() + " · " + HM.format(s.start()); // the clip's own offset
    }

    private String cameraUrl(String camera) {
        return base() + "/admin/cameras?camera=" + URLEncoder.encode(camera, StandardCharsets.UTF_8).replace("+", "%20")
                + "&trigger=person";
    }

    private String base() {
        return props.getPublicUrl().replaceAll("/+$", "");
    }

    private State load() {
        String value = settings.findById(STATE_KEY).map(CameraSetting::getValue).orElse("");
        Map<String, Integer> pending = new LinkedHashMap<>();
        if (value.isBlank()) return new State(null, pending);
        try {
            JsonNode n = json.readTree(value);
            String last = ClipFiles.textOrNull(n.get("last_push"));
            n.path("pending").properties().forEach(e -> {
                if (e.getValue().canConvertToInt() && e.getValue().asInt() > 0) pending.put(e.getKey(), e.getValue().asInt());
            });
            return new State(last == null ? null : Instant.parse(last), pending);
        } catch (RuntimeException e) {
            log.warn("person push state unreadable, starting over: {}", e.getClass().getSimpleName());
            return new State(null, new LinkedHashMap<>());
        }
    }

    private void save(State state) {
        String value = encode(state.lastPush(), state.pending());
        if (value.length() > MAX_STATE_LENGTH) {
            // very many cameras: keep the total, lose the per-camera split
            value = encode(state.lastPush(), Map.of(OTHER, state.total()));
        }
        settings.save(new CameraSetting(STATE_KEY, value));
    }

    private String encode(Instant lastPush, Map<String, Integer> pending) {
        ObjectNode root = json.createObjectNode();
        if (lastPush == null) root.putNull("last_push");
        else root.put("last_push", lastPush.toString());
        ObjectNode p = root.putObject("pending");
        pending.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(e -> p.put(e.getKey(), e.getValue()));
        return json.writeValueAsString(root);
    }
}
