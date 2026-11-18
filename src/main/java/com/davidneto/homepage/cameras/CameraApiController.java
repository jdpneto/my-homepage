package com.davidneto.homepage.cameras;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JSON + media API for the web page and the apps. Security: {@link CameraSecurityConfig}. */
@RestController
@RequestMapping("/api/cameras")
public class CameraApiController {

    private static final MediaType MP4 = MediaType.parseMediaType("video/mp4");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final ClipService clips;

    public CameraApiController(ClipService clips) {
        this.clips = clips;
    }

    @GetMapping("/clips")
    public Map<String, Object> list(@RequestParam(required = false) String camera,
                                    @RequestParam(required = false) String trigger,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                    @RequestParam(required = false) Boolean kept,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(required = false, defaultValue = "50") int limit) {
        ClipQuery q = new ClipQuery(camera, trigger, from, to, kept, ClipCursor.parse(before), limit);
        List<ClipView> page = clips.list(q);
        Map<String, Object> body = new HashMap<>();
        body.put("clips", page);
        body.put("nextBefore", page.size() == q.effectiveLimit() ? ClipCursor.of(page.get(page.size() - 1)).toString() : null);
        return body;
    }

    @GetMapping("/clips/{id}")
    public ClipView get(@PathVariable long id) {
        return clips.get(id).orElseThrow(() -> new ClipNotFoundException(id));
    }

    @GetMapping("/clips/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable long id) {
        Path mp4 = existing(clips.videoPath(id), id);
        return ResponseEntity.ok()
                .contentType(MP4)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new FileSystemResource(mp4));
    }

    @GetMapping("/clips/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable long id) {
        ClipView v = get(id);
        Path mp4 = existing(clips.videoPath(id), id);
        String name = v.camera().replaceAll("[^\\p{L}\\p{N} _-]", "_") + "_" + FILE_TIME.format(v.start()) + ".mp4";
        return ResponseEntity.ok()
                .contentType(MP4)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(mp4));
    }

    @GetMapping("/clips/{id}/thumb")
    public ResponseEntity<Resource> thumb(@PathVariable long id) {
        return clips.thumbPath(id)
                .<ResponseEntity<Resource>>map(p -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                        .body(new FileSystemResource(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/clips/{id}/keep")
    public ClipView keep(@PathVariable long id) {
        return clips.keep(id, true);
    }

    @DeleteMapping("/clips/{id}/keep")
    public ClipView unkeep(@PathVariable long id) {
        return clips.keep(id, false);
    }

    @DeleteMapping("/clips/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        clips.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cameras")
    public List<CameraInfo> cameras() {
        return clips.cameras();
    }

    @GetMapping("/status")
    public StorageStatus status() {
        return clips.status();
    }

    private static Path existing(Path p, long id) {
        if (!Files.isRegularFile(p)) throw new ClipNotFoundException(id);
        return p;
    }
}
