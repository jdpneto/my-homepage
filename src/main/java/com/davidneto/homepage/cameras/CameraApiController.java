package com.davidneto.homepage.cameras;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    private static final DateTimeFormatter ZIP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final ClipService clips;
    private final Clock clock;

    public CameraApiController(ClipService clips, Clock clock) {
        this.clips = clips;
        this.clock = clock;
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

    /** Deletes exactly these clips (1–500 ids); kept clips are skipped and reported. */
    @PostMapping("/clips/delete")
    public BulkDeleteResult deleteMany(@RequestBody BulkDeleteRequest body) {
        return clips.deleteMany(requireIds(body == null ? null : body.ids()));
    }

    public record BulkDeleteRequest(List<Long> ids) {
    }

    /** Keeps exactly these clips (1–500 ids); already-kept clips count as kept. */
    @PostMapping("/clips/keep")
    public BulkKeepResult keepMany(@RequestBody BulkKeepRequest body) {
        return clips.keepMany(requireIds(body == null ? null : body.ids()));
    }

    public record BulkKeepRequest(List<Long> ids) {
    }

    /**
     * One zip of these clips' videos ({@code ids=1,2,3}, 1–500), streamed straight from the files:
     * entries are the clip stems ({@code <Camera>/<YYYY-MM-DD>/<HH-MM-SS>.mp4}), stored without
     * compression (videos are already compressed). Unknown ids and missing files are skipped; 404
     * when none is left.
     */
    @GetMapping("/clips/zip")
    public void zip(@RequestParam(required = false) String ids, HttpServletResponse response) throws IOException {
        List<ClipService.ClipFile> videos = clips.existingVideos(requireIds(parseIds(ids)));
        if (videos.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "none of these clips has a video");
        // Named in the cameras' local time (the first clip's offset), when the zip was made.
        ZoneOffset offset = videos.get(0).start().getOffset();
        String name = "cameras-" + ZIP_TIME.format(clock.instant().atOffset(offset)) + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (ClipService.ClipFile v : videos) {
                InputStream in;
                try {
                    in = Files.newInputStream(v.mp4());
                } catch (NoSuchFileException e) {
                    continue; // removed since it was listed (retention, a delete)
                }
                try (in) {
                    zip.putNextEntry(new ZipEntry(v.stem() + ".mp4"));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
    }

    private static List<Long> parseIds(String ids) {
        if (ids == null) return null;
        List<Long> out = new ArrayList<>();
        for (String part : ids.split(",", -1)) {
            try {
                out.add(Long.parseLong(part.strip()));
            } catch (NumberFormatException e) {
                throw badIds();
            }
        }
        return out;
    }

    private static List<Long> requireIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK || ids.contains(null)) throw badIds();
        return ids;
    }

    private static ResponseStatusException badIds() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids: 1 to " + MAX_BULK + " clip ids");
    }

    static final int MAX_BULK = 500;

    @GetMapping("/cameras")
    public List<CameraSummary> cameras() {
        return clips.cameraSummaries();
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
