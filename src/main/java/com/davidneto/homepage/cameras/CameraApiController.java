package com.davidneto.homepage.cameras;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cameras")
public class CameraApiController {

    private final ClipService clips;

    public CameraApiController(ClipService clips) {
        this.clips = clips;
    }

    @GetMapping("/cameras")
    public List<String> cameras() {
        return clips.cameras();
    }
}
