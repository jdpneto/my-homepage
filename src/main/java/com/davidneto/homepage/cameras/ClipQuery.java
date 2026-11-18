package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;

/** Filters for listing clips; nulls mean "any". {@code beforeId} is the keyset cursor. */
public record ClipQuery(String camera, String trigger, OffsetDateTime from, OffsetDateTime to, Boolean kept,
                        Long beforeId, int limit) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public int effectiveLimit() {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
