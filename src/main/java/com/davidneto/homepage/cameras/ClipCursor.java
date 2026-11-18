package com.davidneto.homepage.cameras;

import java.time.Instant;

/**
 * Keyset cursor "<start epoch millis>_<id>". Self-contained, so it stays valid when the clip it
 * points at is deleted (by the user, the cleanup or the indexer) between two pages.
 */
public record ClipCursor(Instant start, long id) {

    public static ClipCursor of(ClipView v) {
        return new ClipCursor(v.start().toInstant(), v.id());
    }

    public static ClipCursor parse(String s) {
        if (s == null || s.isBlank()) return null;
        int sep = s.indexOf('_');
        try {
            if (sep <= 0) throw new NumberFormatException();
            return new ClipCursor(Instant.ofEpochMilli(Long.parseLong(s.substring(0, sep))),
                    Long.parseLong(s.substring(sep + 1)));
        } catch (NumberFormatException e) {
            throw new BadCursorException(s);
        }
    }

    @Override
    public String toString() {
        return start.toEpochMilli() + "_" + id;
    }

    public static class BadCursorException extends IllegalArgumentException {
        public BadCursorException(String s) {
            super("bad cursor: " + s);
        }
    }
}
