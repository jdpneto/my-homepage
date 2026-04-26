package com.davidneto.homepage.gallery.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
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
            runner.run(List.of(
                    "ffmpeg", "-y", "-ss", "00:00:01", "-i", src.toString(),
                    "-vframes", "1", "-vf", "scale=1600:-2", out.toString()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ffmpeg failed", e);
        }
    }

    private static void runFfmpeg(List<String> args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        p.waitFor();
        if (p.exitValue() != 0) throw new RuntimeException("ffmpeg exit " + p.exitValue());
    }
}
