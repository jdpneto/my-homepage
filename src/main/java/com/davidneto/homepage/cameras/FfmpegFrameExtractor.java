package com.davidneto.homepage.cameras;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Poster frame 1 s in (past the camera's wake-up frames), 480 px wide. Clips shorter than
 * 1 s produce no frame with a seek, so it retries from the first frame.
 */
@Component
public class FfmpegFrameExtractor implements FrameExtractor {

    private static final long TIMEOUT_SECONDS = 60;

    @Override
    public void extract(Path mp4, Path jpg) throws IOException {
        run(mp4, jpg, true);
        if (!Files.exists(jpg) || Files.size(jpg) == 0) {
            run(mp4, jpg, false);
        }
        if (!Files.exists(jpg) || Files.size(jpg) == 0) {
            throw new IOException("ffmpeg produced no frame for " + mp4.getFileName());
        }
    }

    private static void run(Path mp4, Path jpg, boolean seek) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y"));
        if (seek) cmd.addAll(List.of("-ss", "1"));
        cmd.addAll(List.of("-i", mp4.toString(), "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4",
                jpg.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg timed out on " + mp4.getFileName());
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
