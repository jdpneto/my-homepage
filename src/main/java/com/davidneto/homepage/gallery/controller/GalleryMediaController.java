package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.service.GalleryItemService;
import com.davidneto.homepage.gallery.service.GalleryStorage;
import com.davidneto.homepage.gallery.service.MediaTypeSniffer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

@RestController
@RequestMapping("/mae/media")
public class GalleryMediaController {

    private final GalleryItemService items;
    private final GalleryStorage storage;

    public GalleryMediaController(GalleryItemService items, GalleryStorage storage) {
        this.items = items;
        this.storage = storage;
    }

    @GetMapping("/thumb/{id}")
    public ResponseEntity<?> thumb(@PathVariable long id) {
        return items.find(id).<ResponseEntity<?>>map(item ->
                serve(storage.thumbPath(item.getStorageKey()), MediaType.IMAGE_JPEG_VALUE, null)
        ).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/display/{id}")
    public ResponseEntity<?> display(@PathVariable long id) {
        return items.find(id).<ResponseEntity<?>>map(item ->
                serve(storage.displayPath(item.getStorageKey()), MediaType.IMAGE_JPEG_VALUE, null)
        ).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/original/{id}")
    public ResponseEntity<?> original(@PathVariable long id) {
        return items.find(id).<ResponseEntity<?>>map(item -> {
            String ext = MediaTypeSniffer.extensionFor(item.getContentType());
            Path p = storage.originalPath(item.getStorageKey(), ext);
            String disp = "attachment; filename=\"" + item.getOriginalFilename().replace("\"", "_") + "\"";
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disp)
                    .contentType(MediaType.parseMediaType(item.getContentType()))
                    .body(new FileSystemResource(p));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<?> serve(Path p, String contentType, String contentDisposition) {
        FileSystemResource res = new FileSystemResource(p);
        if (!res.exists()) return ResponseEntity.notFound().build();
        var b = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .contentType(MediaType.parseMediaType(contentType));
        if (contentDisposition != null) b.header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        return b.body(res);
    }
}
