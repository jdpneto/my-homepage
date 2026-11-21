package com.davidneto.homepage.cameras;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote mode switch (disarm / home / away). POST writes the request file; the Mac mini applies
 * it within a cycle and GET reports pending → applied / failed / expired.
 */
@RestController
@RequestMapping("/api/cameras/mode")
public class CameraModeController {

    public record SwitchRequest(String mode) {
    }

    private final ModeRequests requests;

    public CameraModeController(ModeRequests requests) {
        this.requests = requests;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> request(@RequestBody SwitchRequest body) {
        if (ModeRequests.normalise(body.mode()) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be disarm, home or away"));
        }
        ModeRequests.ModeRequest r = requests.create(body.mode());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("id", r.id(), "mode", r.mode(), "status", "pending"));
    }

    @GetMapping
    public Map<String, Object> state() {
        ModeRequests.ModeState s = requests.state();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", s.current());
        if (s.request() == null) {
            out.put("request", null);
        } else {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", s.request().id());
            r.put("mode", s.request().mode());
            r.put("status", s.request().status());
            r.put("error", s.request().error());
            r.put("requested_at", java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(s.request().requestedAt()));
            out.put("request", r);
        }
        return out;
    }
}
