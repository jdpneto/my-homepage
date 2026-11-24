package com.davidneto.homepage.cameras;

import java.time.OffsetDateTime;

/** An older day on the camera page, rendered collapsed: "Yesterday · 412 clips · 492 MB ▸". */
public record ClipDayRow(String label, String day, long count, long bytes, String meta, OffsetDateTime from,
                         OffsetDateTime to) {
}
