package com.davidneto.homepage.cameras;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/cameras/alerts")
public class CameraAlertsController {

    public record MuteRequest(Boolean muted, OffsetDateTime until) {
    }

    private final Notifier notifier;
    private final AlertMute mute;

    public CameraAlertsController(Notifier notifier, AlertMute mute) {
        this.notifier = notifier;
        this.mute = mute;
    }

    /** "Send test alert" button: true when ntfy accepted it (sent even while alerts are muted). */
    @PostMapping("/test")
    public Map<String, Boolean> test() {
        return Map.of("sent", notifier.send("Test alert from davidneto.eu",
                "If you can read this, camera alerts reach this phone.", "default"));
    }

    @GetMapping("/mute")
    public AlertMute.MuteState getMute() {
        return mute.get();
    }

    @PutMapping("/mute")
    public AlertMute.MuteState setMute(@RequestBody MuteRequest body) {
        boolean muted = Boolean.TRUE.equals(body.muted());
        return mute.set(muted, body.until() == null ? null : body.until().toInstant());
    }
}
