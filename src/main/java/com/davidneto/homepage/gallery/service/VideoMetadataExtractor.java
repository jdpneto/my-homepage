package com.davidneto.homepage.gallery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class VideoMetadataExtractor {

    public interface ProcessRunner {
        String run(List<String> command) throws Exception;
    }

    public record Result(LocalDateTime takenAt, Integer width, Integer height, Integer durationSeconds) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProcessRunner runner;

    public VideoMetadataExtractor() {
        this(VideoMetadataExtractor::runFfprobe);
    }

    public VideoMetadataExtractor(ProcessRunner runner) {
        this.runner = runner;
    }

    public Result extract(Path file) {
        String json;
        try {
            json = runner.run(List.of(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", file.toString()));
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
        if (json == null || json.isBlank()) return new Result(null, null, null, null);
        try {
            JsonNode root = MAPPER.readTree(json);
            Integer width = null, height = null;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    width = stream.path("width").isMissingNode() ? null : stream.path("width").asInt();
                    height = stream.path("height").isMissingNode() ? null : stream.path("height").asInt();
                    break;
                }
            }
            Integer dur = null;
            JsonNode durNode = root.path("format").path("duration");
            if (durNode.isTextual()) {
                try { dur = (int) Math.floor(Double.parseDouble(durNode.asText())); } catch (NumberFormatException ignored) {}
            }
            LocalDateTime taken = null;
            JsonNode created = root.path("format").path("tags").path("creation_time");
            if (created.isTextual()) {
                try {
                    taken = LocalDateTime.ofInstant(
                            DateTimeFormatter.ISO_DATE_TIME.parse(created.asText(), java.time.Instant::from),
                            ZoneOffset.UTC);
                } catch (Exception ignored) {}
            }
            return new Result(taken, width, height, dur);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }

    private static String runFfprobe(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String out = br.lines().collect(Collectors.joining("\n"));
            p.waitFor();
            if (p.exitValue() != 0) throw new RuntimeException("ffprobe exit " + p.exitValue());
            return out;
        }
    }
}
