package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;
import java.util.List;

public record ClipView(long id, String camera, String cameraId, OffsetDateTime start, OffsetDateTime end,
                       int durationSeconds, List<String> triggers, long sizeBytes, boolean kept) {
}
