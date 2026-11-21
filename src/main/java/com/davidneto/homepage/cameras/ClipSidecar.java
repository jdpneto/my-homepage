package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The uploader's per-clip {@code .json} (only the fields the app uses). {@code mode} is the hub's
 * active mode when the clip was uploaded ({@code away}, {@code home}, ...), null when unknown.
 */
public record ClipSidecar(String cameraName, String cameraId, int channel, OffsetDateTime start,
                          OffsetDateTime end, int durationSeconds, List<String> triggers, String mode) {
}
