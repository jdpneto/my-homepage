package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;

/**
 * One calendar day of clips in the cameras' own offset: how many and how big, and its bounds
 * ({@code from} inclusive, {@code to} exclusive) for listing that day's clips.
 */
public record ClipDay(String day, long count, long bytes, OffsetDateTime from, OffsetDateTime to) {
}
