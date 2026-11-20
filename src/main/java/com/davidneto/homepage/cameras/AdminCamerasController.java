package com.davidneto.homepage.cameras;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The camera page in the admin area; the interactive parts live in /js/cameras.js. */
@Controller
@RequestMapping("/admin/cameras")
public class AdminCamerasController {

    static final int PAGE_SIZE = 50;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final ClipService clips;
    private final AlertMute mute;
    private final Clock clock;

    public AdminCamerasController(ClipService clips, AlertMute mute, Clock clock) {
        this.clips = clips;
        this.mute = mute;
        this.clock = clock;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String camera,
                       @RequestParam(required = false) String trigger,
                       @RequestParam(required = false) Boolean kept,
                       Model model, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        String cam = blankToNull(camera), trig = blankToNull(trigger);
        List<ClipView> page = clips.list(new ClipQuery(cam, trig, null, null, kept, null, PAGE_SIZE));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        StorageStatus status = clips.status();

        model.addAttribute("groups", group(page, now));
        List<CameraSummary> cameras = clips.cameraSummaries();
        model.addAttribute("cameras", cameras);
        java.util.Map<String, String> lastClip = new java.util.HashMap<>();
        for (CameraSummary c : cameras) {
            if (c.latestStart() != null) lastClip.put(c.name(), ago(Duration.between(c.latestStart(), now)));
        }
        model.addAttribute("lastClipAgo", lastClip);
        model.addAttribute("status", status);
        model.addAttribute("camera", cam);
        model.addAttribute("trigger", trig);
        model.addAttribute("kept", kept);
        model.addAttribute("nextBefore", page.size() == PAGE_SIZE ? ClipCursor.of(page.get(page.size() - 1)).toString() : null);
        model.addAttribute("query", query(cam, trig, kept));
        model.addAttribute("now", now);

        long free = status.quotaBytes() > 0 ? Math.max(0, status.quotaBytes() - status.quotaUsed()) : status.volumeFree();
        model.addAttribute("storageText", "Kept " + size(status.keptBytes()) + " · Last 30 days "
                + size(status.unkeptBytes()) + " · Free " + size(free));
        model.addAttribute("storageLevel", level(status));
        model.addAttribute("healthText", health(status, now));
        // Show times in the cameras' own offset (the server runs in UTC): the newest clip of any
        // camera, never the filtered list (which can be empty or months old). The browser
        // re-renders it in its own time zone from data-until anyway.
        ZoneOffset localOffset = cameras.stream().map(CameraSummary::latestStart).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).map(OffsetDateTime::getOffset).orElse(ZoneOffset.UTC);
        model.addAttribute("alertsText", alertsText(mute.get(), localOffset));
        model.addAttribute("alertsMuted", mute.get().muted());
        model.addAttribute("alertsUntil", mute.get().until() == null ? null : mute.get().until().toString());
        return "admin/cameras";
    }

    /** Day headings in each clip's own offset: Today, Yesterday, else "Sun 16 Nov". */
    static List<ClipDayGroup> group(List<ClipView> clips, OffsetDateTime now) {
        List<ClipDayGroup> groups = new ArrayList<>();
        String current = null;
        List<ClipView> bucket = null;
        for (ClipView c : clips) {
            String label = dayLabel(c.start(), now);
            if (!label.equals(current)) {
                bucket = new ArrayList<>();
                groups.add(new ClipDayGroup(label, bucket));
                current = label;
            }
            bucket.add(c);
        }
        return groups;
    }

    static String dayLabel(OffsetDateTime start, OffsetDateTime now) {
        LocalDate day = start.toLocalDate();
        LocalDate today = now.withOffsetSameInstant(start.getOffset()).toLocalDate();
        if (day.equals(today)) return "Today";
        if (day.equals(today.minusDays(1))) return "Yesterday";
        return DAY.format(day);
    }

    static String size(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format(Locale.ENGLISH, "%.1f %s", v, units[u]);
    }

    private static String level(StorageStatus s) {
        if (s.warnings().stream().anyMatch(w -> w.contains("missing"))) return "red";
        return s.warnings().isEmpty() ? "ok" : "amber";
    }

    private static String health(StorageStatus s, OffsetDateTime now) {
        String uploader = s.heartbeatAt() == null ? "Uploader: no heartbeat yet"
                : "Uploader: last heartbeat " + ago(Duration.between(s.heartbeatAt(), now));
        String hub = s.hubOk() == null ? "Hub unknown" : s.hubOk() ? "Hub OK" : "Hub DOWN";
        return uploader + " · " + hub;
    }

    static String ago(Duration d) {
        long m = Math.max(0, d.toMinutes());
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        return (m / (24 * 60)) + " days ago";
    }

    private static String alertsText(AlertMute.MuteState m, ZoneOffset offset) {
        if (!m.muted()) return "Alerts on";
        if (m.until() == null) return "Alerts off";
        return "Alerts muted until " + HM.format(m.until().atOffset(offset));
    }

    /** Query string for the script's page requests; every value fully encoded ('+' too, which a server reads as a space). */
    private static String query(String camera, String trigger, Boolean kept) {
        List<String> parts = new ArrayList<>();
        if (camera != null) parts.add("camera=" + enc(camera));
        if (trigger != null) parts.add("trigger=" + enc(trigger));
        if (kept != null) parts.add("kept=" + kept);
        return String.join("&", parts);
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
