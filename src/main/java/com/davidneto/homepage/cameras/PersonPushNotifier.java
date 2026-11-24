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
 * A push when a person or motion is recorded while the hub is in away mode: the clip's triggers
 * contain {@code person} or {@code motion} (animal-only clips never count), its sidecar mode is
 * {@code away} and it started at most 30 min before it was indexed (so a backlog or re-index never
 * floods). The title says "Person" when person is among the triggers, else "Motion".
 * <p>
 * Two hourly slots, so wind in the trees never holds back a person: a motion clip pushes at once
 * only when no push went out in the last hour and nothing is pending; a person clip pushes at once
 * when no person push (single, or a summary with a person in it) went out in the last hour, unless
 * a summary is due right now, which it then joins. A person push that finds the motion hour free
 * starts it too (no motion push a minute later). Everything else is counted per camera and sent as
 * one summary when the hour since the last push ends, which starts the next hour. While alerts are
 * muted nothing is sent and the counts are dropped. A push only counts once ntfy accepted it;
 * undelivered ones stay pending for the next flush (an undelivered person push is retried as soon
 * as its slot allows, not at the end of the motion hour). State lives in one
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
    static final String MOTION_TAGS = "warning";
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

    /**
     * Last delivered push (single or summary), last delivered person push (single, or a summary
     * with a person in it) and the clips counted since, per camera; {@code person}: a person clip
     * is among them (the summary's tag).
     */
    private record State(Instant lastPush, Instant lastPersonPush, Map<String, Integer> pending, boolean person) {
        State(Instant lastPush, Instant lastPersonPush, Map<String, Integer> pending) {
            this(lastPush, lastPersonPush, pending, false);
        }

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
                if (!state.pending().isEmpty()) {
                    save(new State(state.lastPush(), state.lastPersonPush(), new LinkedHashMap<>()));
                }
                return;
            }
            boolean hourOver = hourIsOver(state.lastPush(), now);
            boolean summaryDue = hourOver && !state.pending().isEmpty();
            boolean person = hasPerson(s);
            boolean single = person ? hourIsOver(state.lastPersonPush(), now) && !summaryDue
                    : hourOver && state.pending().isEmpty();
            boolean attempted = false;
            if (single) {
                if (notifier.send(title(s), "Away mode", PRIORITY, person ? TAGS : MOTION_TAGS,
                        cameraUrl(s.cameraName(), person))) {
                    log.info("away push sent: {} ({})", s.cameraName(), person ? "person" : "motion");
                    // a person push takes the motion hour only when it was free: a pending summary keeps its time
                    boolean motionHourFree = hourOver && state.pending().isEmpty();
                    save(new State(motionHourFree ? now : state.lastPush(), person ? now : state.lastPersonPush(),
                            state.pending(), state.person()));
                    return;
                }
                attempted = true;
                log.warn("person push not delivered, kept for the next summary: {}", s.cameraName());
            }
            Map<String, Integer> pending = new LinkedHashMap<>(state.pending());
            pending.merge(s.cameraName(), 1, Integer::sum);
            State counted = new State(state.lastPush(), state.lastPersonPush(), pending, state.person() || person);
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

    /**
     * Sends the pending summary once the hour since the last push is over, or once the person
     * slot is free when a person clip is pending.
     */
    public synchronized void flush() {
        try {
            State state = load();
            if (state.pending().isEmpty()) return;
            if (mute.get().muted()) {
                save(new State(state.lastPush(), state.lastPersonPush(), new LinkedHashMap<>()));
                return;
            }
            Instant now = clock.instant();
            // an undelivered person push goes out as soon as the person slot allows
            if (hourIsOver(state.lastPush(), now) || (state.person() && hourIsOver(state.lastPersonPush(), now))) {
                sendSummary(state, now);
            }
        } catch (RuntimeException e) {
            log.warn("person push summary failed: {}", e.getClass().getSimpleName());
        }
    }

    private void sendSummary(State state, Instant now) {
        int n = state.total();
        String title = n + " more " + (n == 1 ? "clip" : "clips") + " while away";
        String body = state.pending().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        if (notifier.send(title, body, PRIORITY, state.person() ? TAGS : MOTION_TAGS, base() + "/admin/cameras")) {
            log.info("away push summary sent: {}", title);
            save(new State(now, state.person() ? now : state.lastPersonPush(), new LinkedHashMap<>()));
        } else {
            log.warn("away push summary not delivered, will retry: {}", title);
        }
    }

    private static boolean qualifies(ClipSidecar s, Instant indexedAt) {
        if (s == null || s.start() == null || s.triggers() == null || s.cameraName() == null) return false;
        boolean away = s.mode() != null && s.mode().trim().equalsIgnoreCase("away");
        boolean recent = !s.start().toInstant().isBefore(indexedAt.minus(MAX_CLIP_AGE));
        return (hasPerson(s) || hasTrigger(s, "motion")) && away && recent;
    }

    private static boolean hasPerson(ClipSidecar s) {
        return hasTrigger(s, "person");
    }

    private static boolean hasTrigger(ClipSidecar s, String trigger) {
        return s.triggers().stream().anyMatch(t -> t != null && t.trim().equalsIgnoreCase(trigger));
    }

    private static boolean hourIsOver(Instant last, Instant now) {
        return last == null || !now.isBefore(last.plus(EVERY));
    }

    private static String title(ClipSidecar s) {
        return (hasPerson(s) ? "Person" : "Motion") + " · " + s.cameraName() + " · " + HM.format(s.start()); // the clip's own offset
    }

    /** The camera's clips; a person push filters on person too. */
    private String cameraUrl(String camera, boolean person) {
        return base() + "/admin/cameras?camera=" + URLEncoder.encode(camera, StandardCharsets.UTF_8).replace("+", "%20")
                + (person ? "&trigger=person" : "");
    }

    private String base() {
        return props.getPublicUrl().replaceAll("/+$", "");
    }

    private State load() {
        String value = settings.findById(STATE_KEY).map(CameraSetting::getValue).orElse("");
        Map<String, Integer> pending = new LinkedHashMap<>();
        if (value.isBlank()) return new State(null, null, pending);
        try {
            JsonNode n = json.readTree(value);
            String last = ClipFiles.textOrNull(n.get("last_push"));
            String lastPerson = ClipFiles.textOrNull(n.get("last_person_push"));
            n.path("pending").properties().forEach(e -> {
                if (e.getValue().canConvertToInt() && e.getValue().asInt() > 0) pending.put(e.getKey(), e.getValue().asInt());
            });
            boolean person = n.path("person").asBoolean(false);
            return new State(last == null ? null : Instant.parse(last),
                    lastPerson == null ? null : Instant.parse(lastPerson), pending, person);
        } catch (RuntimeException e) {
            log.warn("person push state unreadable, starting over: {}", e.getClass().getSimpleName());
            return new State(null, null, new LinkedHashMap<>());
        }
    }

    private void save(State state) {
        boolean person = state.person() && !state.pending().isEmpty();
        String value = encode(state, state.pending(), person);
        if (value.length() > MAX_STATE_LENGTH) {
            // very many cameras: keep the total, lose the per-camera split
            value = encode(state, Map.of(OTHER, state.total()), person);
        }
        settings.save(new CameraSetting(STATE_KEY, value));
    }

    private String encode(State state, Map<String, Integer> pending, boolean person) {
        ObjectNode root = json.createObjectNode();
        if (state.lastPush() == null) root.putNull("last_push");
        else root.put("last_push", state.lastPush().toString());
        if (state.lastPersonPush() != null) root.put("last_person_push", state.lastPersonPush().toString());
        if (person) root.put("person", true);
        ObjectNode p = root.putObject("pending");
        pending.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(e -> p.put(e.getKey(), e.getValue()));
        return json.writeValueAsString(root);
    }
}
