package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record StorageStatus(boolean volumeOk, long volumeTotal, long volumeFree, long quotaBytes, long quotaUsed,
                            long keptBytes, long unkeptBytes, long rootTotal, long rootFree,
                            Map<String, OffsetDateTime> newestByCamera, OffsetDateTime heartbeatAt, Boolean hubOk,
                            List<String> warnings) {
}
