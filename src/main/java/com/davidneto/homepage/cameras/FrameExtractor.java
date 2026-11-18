package com.davidneto.homepage.cameras;

import java.io.IOException;
import java.nio.file.Path;

/** Writes one JPEG frame of {@code mp4} to {@code jpg}. */
@FunctionalInterface
public interface FrameExtractor {
    void extract(Path mp4, Path jpg) throws IOException;
}
