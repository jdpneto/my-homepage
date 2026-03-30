package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.service.ImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/images")
public class ImageApiController {

    private final ImageService imageService;

    public ImageApiController(ImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam OwnerType ownerType,
                                    @RequestParam Long ownerId,
                                    @RequestParam MultipartFile file) {
        try {
            Image image = imageService.upload(ownerType, ownerId, file);
            return ResponseEntity.ok(toJson(image));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam OwnerType ownerType,
                                                           @RequestParam Long ownerId) {
        List<Map<String, Object>> result = imageService.listByOwner(ownerType, ownerId)
                .stream()
                .map(this::toJson)
                .toList();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        imageService.delete(id);
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> toJson(Image image) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", image.getId());
        map.put("filename", image.getFilename());
        map.put("url", imageService.getImageUrl(image));
        map.put("createdAt", image.getCreatedAt());
        return map;
    }
}
