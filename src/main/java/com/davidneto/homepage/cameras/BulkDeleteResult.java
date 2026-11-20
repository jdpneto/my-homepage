package com.davidneto.homepage.cameras;

import java.util.List;

/** Per-id outcome of a bulk delete: kept clips are skipped, never deleted. */
public record BulkDeleteResult(List<Long> deleted, long freedBytes, List<Long> skippedKept, List<Long> notFound,
                               List<Long> failed) {
}
