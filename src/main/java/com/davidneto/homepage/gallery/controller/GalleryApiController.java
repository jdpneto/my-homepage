package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.service.GalleryIngestService;
import com.davidneto.homepage.gallery.service.GalleryItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/mae/api/items")
public class GalleryApiController {

    private final GalleryIngestService ingest;
    private final GalleryItemService items;

    public GalleryApiController(GalleryIngestService ingest, GalleryItemService items) {
        this.ingest = ingest;
        this.items = items;
    }

    @PostMapping
    public ResponseEntity<List<Map<String, Object>>> upload(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(value = "uploaderName", required = false) String uploaderName,
            @RequestParam(value = "caption", required = false) String caption) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MultipartFile f : files) {
            try {
                GalleryIngestService.IngestResult r = ingest.ingest(
                        f.getInputStream(), f.getOriginalFilename(),
                        f.getContentType(), uploaderName);
                if (caption != null && !caption.isBlank() && !r.deduped()) {
                    items.updateCaption(r.itemId(), caption, uploaderName);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", r.itemId());
                entry.put("deduped", r.deduped());
                result.add(entry);
            } catch (GalleryIngestService.UnsupportedMediaException e) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", f.getOriginalFilename());
                entry.put("error", e.getMessage());
                result.add(entry);
            } catch (IOException e) {
                // Don't fail the whole batch on one bad file (e.g. ffmpeg can't
                // produce a poster). Mark this item as errored and continue.
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filename", f.getOriginalFilename());
                entry.put("error", e.getMessage() == null ? "ingest failed" : e.getMessage());
                result.add(entry);
            }
        }
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> patchCaption(
            @PathVariable long id,
            @RequestBody CaptionUpdate body,
            Authentication auth) {
        try {
            GalleryItem item = items.updateCaption(id, body.caption(),
                    auth == null ? null : auth.getName());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.getId());
            entry.put("caption", item.getCaption());
            return ResponseEntity.ok(entry);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) throws IOException {
        try {
            items.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record CaptionUpdate(String caption) {}
}
