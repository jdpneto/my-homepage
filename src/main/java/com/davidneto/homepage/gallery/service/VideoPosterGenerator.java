package com.davidneto.homepage.gallery.service;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class VideoPosterGenerator {

    public interface PosterRunner {
        void run(List<String> args) throws Exception;
    }

    private final PosterRunner runner;

    public VideoPosterGenerator() {
        this(VideoPosterGenerator::runFfmpeg);
    }

    public VideoPosterGenerator(PosterRunner runner) {
        this.runner = runner;
    }

    public void writePoster(Path src, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        try {
            // First frame, no seek: avoids the "ffmpeg silently produces no
            // output if the seek lands past the end" failure mode on short
            // clips (e.g. iPhone 1-second snippets) where -ss 00:00:01 + -vframes 1
            // exits 0 but writes nothing.
            runner.run(List.of(
                    "ffmpeg", "-y", "-i", src.toString(),
                    "-frames:v", "1", "-vf", "scale=1600:-2", out.toString()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ffmpeg failed for " + src.getFileName() + ": " + e.getMessage(), e);
        }
        // Defensive check: if ffmpeg exited 0 but for any reason no output
        // was written, fail explicitly rather than letting the next step
        // (Thumbnailator) throw a confusing "Can't read input file!".
        if (!Files.exists(out) || Files.size(out) == 0) {
            throw new IOException("ffmpeg produced no poster output for " + src.getFileName());
        }
    }

    private static void runFfmpeg(List<String> args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        // Capture the tail of ffmpeg's stderr so failures surface a useful
        // message rather than just the exit code.
        StringBuilder tail = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int kept = 0;
            while ((line = br.readLine()) != null) {
                if (kept < 20) { tail.append(line).append('\n'); kept++; }
            }
        }
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new RuntimeException("ffmpeg exit " + p.exitValue() + ": " + tail);
        }
    }
}
