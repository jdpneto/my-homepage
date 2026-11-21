package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The camera volume on disk: {@code <Camera>/<YYYY-MM-DD>/<HH-MM-SS>[-N].mp4} plus a same-stem
 * {@code .json} sidecar and an optional {@code .keep} marker. Files are the source of truth.
 */
@Component
public class ClipFiles {

    private static final Logger log = LoggerFactory.getLogger(ClipFiles.class);

    public static final String VOLUME_MARKER = "volume-marker.txt";
    public static final String MARKER_TEXT = "camera clips live on the 15 GB volume";
    public static final String HEARTBEAT = "heartbeat.json";
    /** A clip stem relative to the root: camera folder (one segment), date, time[-N]. */
    public static final Pattern STEM = Pattern.compile("^[^/\\\\]+/\\d{4}-\\d{2}-\\d{2}/\\d{2}-\\d{2}-\\d{2}(-\\d+)?$");

    private final CameraProperties props;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ClipFiles(CameraProperties props) {
        this.props = props;
    }

    public Path root() {
        return props.getRootDir();
    }

    /** True only when the marker exists on the volume and has the known text. */
    public boolean volumeOk() {
        Path marker = root().resolve(VOLUME_MARKER);
        try {
            return Files.isRegularFile(marker) && Files.readString(marker).startsWith(MARKER_TEXT);
        } catch (IOException e) {
            return false;
        }
    }

    /** Stems of every {@code .mp4} that matches the layout. */
    public List<String> listStems() {
        Path root = root();
        if (!Files.isDirectory(root)) return List.of();
        List<String> stems = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 3)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".mp4") && Files.isRegularFile(p))
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String stem = rel.substring(0, rel.length() - ".mp4".length());
                        if (STEM.matcher(stem).matches()) stems.add(stem);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stems;
    }

    public Optional<ClipSidecar> readSidecar(String stem) {
        Path file = resolve(stem, ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode n = json.readTree(Files.readString(file));
            List<String> triggers = new ArrayList<>();
            n.path("triggers").forEach(t -> triggers.add(t.asString()));
            return Optional.of(new ClipSidecar(
                    n.path("camera_name").asString(),
                    n.path("camera_id").asString(),
                    n.path("channel").asInt(),
                    OffsetDateTime.parse(n.path("start").asString()),
                    OffsetDateTime.parse(n.path("end").asString()),
                    n.path("duration_seconds").asInt(),
                    List.copyOf(triggers),
                    textOrNull(n.path("mode"))));
        } catch (Exception e) {
            // Half-uploaded or malformed: skip for now, a later pass will retry.
            log.debug("cannot read sidecar {}: {}", stem, e.toString());
            return Optional.empty();
        }
    }

    /** A non-blank string value, else null (absent, JSON null, blank or not a string). */
    static String textOrNull(JsonNode n) {
        if (n == null || !n.isString()) return null;
        String v = n.asString().trim();
        return v.isEmpty() ? null : v;
    }

    /** When the clip's .mp4 arrived on this server (its modification time), if it exists. */
    public java.util.Optional<java.time.Instant> arrivedAt(String stem) {
        try {
            return java.util.Optional.of(Files.getLastModifiedTime(resolve(stem, ".mp4")).toInstant());
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    public Path mp4(String stem) {
        return resolve(stem, ".mp4");
    }

    public boolean isKept(String stem) {
        return Files.isRegularFile(resolve(stem, ".keep"));
    }

    public void setKept(String stem, boolean kept) {
        Path marker = resolve(stem, ".keep");
        try {
            if (kept) {
                if (!Files.exists(marker)) Files.createFile(marker);
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the clip's .mp4, .json and .keep. */
    public void deleteClip(String stem) {
        try {
            Files.deleteIfExists(resolve(stem, ".mp4"));
            Files.deleteIfExists(resolve(stem, ".json"));
            Files.deleteIfExists(resolve(stem, ".keep"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<JsonNode> readHeartbeat() {
        Path file = root().resolve(HEARTBEAT);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(json.readTree(Files.readString(file)));
        } catch (Exception e) {
            log.debug("cannot read heartbeat: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Path for a stem + suffix; refuses anything outside the layout or the root. */
    private Path resolve(String stem, String suffix) {
        if (stem == null || !STEM.matcher(stem).matches()) {
            throw new IllegalArgumentException("not a clip stem: " + stem);
        }
        for (String segment : stem.split("/")) {
            if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("not a clip stem: " + stem);
        }
        Path root = root().toAbsolutePath().normalize();
        Path p = root.resolve(stem + suffix).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("not a clip stem: " + stem);
        return p;
    }
}
