package com.davidneto.homepage.cameras;

import java.util.List;

/** Per-id outcome of a bulk keep: clips already kept count as kept. */
public record BulkKeepResult(List<Long> kept, long keptBytes, List<Long> notFound, List<Long> failed) {
}
