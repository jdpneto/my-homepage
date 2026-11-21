package com.davidneto.homepage.cameras;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves the home-screen app manifest (static/cameras.webmanifest) as application/manifest+json:
 * neither the servlet container's MIME table nor Spring's knows the .webmanifest extension.
 * Public, like the icons; start_url is /admin/cameras, which still asks for a login.
 */
@RestController
public class CamerasManifestController {

    static final MediaType MANIFEST = MediaType.valueOf("application/manifest+json");
    private final Resource manifest = new ClassPathResource("static/cameras.webmanifest");

    @GetMapping("/cameras.webmanifest")
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok()
                .contentType(MANIFEST)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .body(manifest);
    }
}
