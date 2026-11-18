package com.davidneto.homepage.cameras;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cameras/alerts")
public class CameraAlertsController {

    private final Notifier notifier;

    public CameraAlertsController(Notifier notifier) {
        this.notifier = notifier;
    }

    /** "Send test alert" button: true when ntfy accepted it. */
    @PostMapping("/test")
    public Map<String, Boolean> test() {
        return Map.of("sent", notifier.send("Test alert from davidneto.eu",
                "If you can read this, camera alerts reach this phone.", "default"));
    }
}
