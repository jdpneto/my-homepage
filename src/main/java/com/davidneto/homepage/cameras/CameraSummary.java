package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;

public record CameraSummary(String name, String cameraId, long clipCount, Long latestClipId,
                            OffsetDateTime latestStart) {
}
