package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Remote mode switch: the server writes {@value #FILE} at the camera root and the Mac mini's
 * uploader applies it (at most once, never after {@code expires_at}) and reports the result in
 * the heartbeat's {@code mode_request}. WebDAV cannot write the file (WebDavProtectionFilter),
 * so only this class (on disk) can ask for a switch.
 */
@Component
public class ModeRequests {

    private static final Logger log = LoggerFactory.getLogger(ModeRequests.class);

    public static final String FILE = "mode-request.json";
    /** The hub's modes a request may name; never {@code off}. */
    public static final Set<String> MODES = Set.of("disarm", "home", "away");
    public static final Duration EXPIRY = Duration.ofMinutes(5);
    /**
     * How long after {@code expires_at} a request still counts as pending: the Mac mini may pick it
     * up just before expiry, and the switch (up to 30 s), the read-back (15 s) and the heartbeat
     * that reports it land a little after it. Only then is "the Mac mini didn't pick it up" true.
     */
    public static final Duration EXPIRY_GRACE = Duration.ofSeconds(60);
    /** ISO-8601 with a numeric offset ({@code +00:00}, not {@code Z}), seconds precision. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.ROOT);

    public record ModeRequest(String id, String mode, OffsetDateTime requestedAt, OffsetDateTime expiresAt) {
    }

    /** {@code status}: pending | applied | failed | expired. */
    public record RequestStatus(String id, String mode, String status, String error, OffsetDateTime requestedAt) {
        public boolean pending() {
            return "pending".equals(status);
        }
    }

    /** {@code current}: the hub's mode in the last heartbeat (null when unknown). */
    public record ModeState(String current, RequestStatus request) {
    }

    private final ClipFiles files;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();
    /** "id status" of the last result logged, so each result is logged once. */
    private volatile String lastLogged;

    public ModeRequests(ClipFiles files, Clock clock) {
        this.files = files;
        this.clock = clock;
    }

    /** The mode a request may name, normalised; null when it is not one of {@link #MODES}. */
    public static String normalise(String mode) {
        if (mode == null) return null;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return MODES.contains(m) ? m : null;
    }

    /**
     * Writes a new request (replacing any earlier one) atomically: a temp file next to it, then
     * a rename, so the uploader never reads half a file.
     *
     * @throws IllegalArgumentException for {@code off} or an unknown mode
     * @throws VolumeUnavailableException when the camera volume is not mounted
     */
    public ModeRequest create(String mode) {
        String m = normalise(mode);
        if (m == null) throw new IllegalArgumentException("mode must be disarm, home or away");
        if (!files.volumeOk()) {
            throw new VolumeUnavailableException("camera volume not available — the mode was not changed");
        }
        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        ModeRequest r = new ModeRequest(UUID.randomUUID().toString(), m, now, now.plus(EXPIRY));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("mode", r.mode());
        body.put("requested_at", ISO.format(r.requestedAt()));
        body.put("expires_at", ISO.format(r.expiresAt()));

        Path target = files.root().resolve(FILE);
        Path tmp = files.root().resolve("." + FILE + "." + r.id() + ".tmp");
        try {
            Files.writeString(tmp, json.writeValueAsString(body) + "\n");
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new UncheckedIOException(e);
        }
        log.info("camera mode request {}: switch to {} (expires {})", r.id(), r.mode(), body.get("expires_at"));
        return r;
    }

    /** The current request file, if there is a readable one. */
    public Optional<ModeRequest> read() {
        Path file = files.root().resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode n = json.readTree(Files.readString(file));
            String id = ClipFiles.textOrNull(n.get("id"));
            String mode = ClipFiles.textOrNull(n.get("mode"));
            if (id == null || mode == null) return Optional.empty();
            return Optional.of(new ModeRequest(id, mode,
                    OffsetDateTime.parse(n.get("requested_at").asString()),
                    OffsetDateTime.parse(n.get("expires_at").asString())));
        } catch (Exception e) {
            log.debug("cannot read {}: {}", FILE, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The hub's mode and the latest request's status: the heartbeat's result for that id when
     * there is one, else expired once past {@code expires_at} + {@link #EXPIRY_GRACE}, else pending.
     */
    public ModeState state() {
        Optional<JsonNode> hb = files.readHeartbeat();
        String current = hb.map(n -> ClipFiles.textOrNull(n.get("mode"))).orElse(null);
        Optional<ModeRequest> req = read();
        if (req.isEmpty()) return new ModeState(current, null);
        ModeRequest r = req.get();

        String status = "pending";
        String error = null;
        Optional<ClipFiles.ModeRequestResult> result = files.modeRequestResult();
        if (result.isPresent() && result.get().id().equals(r.id())) {
            status = result.get().status();
            error = result.get().error();
        } else if (clock.instant().isAfter(r.expiresAt().toInstant().plus(EXPIRY_GRACE))) {
            status = "expired";
        }
        logResult(r, status, error);
        return new ModeState(current, new RequestStatus(r.id(), r.mode(), status, error, r.requestedAt()));
    }

    private void logResult(ModeRequest r, String status, String error) {
        if ("pending".equals(status)) return;
        String key = r.id() + " " + status;
        if (key.equals(lastLogged)) return;
        lastLogged = key;
        if (error == null) log.info("camera mode request {} ({}): {}", r.id(), r.mode(), status);
        else log.info("camera mode request {} ({}): {} ({})", r.id(), r.mode(), status, error);
    }
}
