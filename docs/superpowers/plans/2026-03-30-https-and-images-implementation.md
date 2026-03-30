# HTTPS & Image Uploads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HTTPS support via Caddy self-signed certs and per-entity image uploads with a gallery UI in the blog post and static page editors.

**Architecture:** Caddy's `tls internal` directive handles self-signed HTTPS. A new `Image` entity with polymorphic ownership (`owner_type` + `owner_id`) stores metadata for images uploaded to blog posts and static pages. A REST API controller serves JSON for the editor's JavaScript gallery. Images are stored on disk under the existing `uploads` volume.

**Tech Stack:** Caddy 2, Spring Boot 3.4, Spring Data JPA, Flyway, JavaScript (vanilla), EasyMDE

---

## File Structure

### New Files
- `src/main/resources/db/migration/V2__add_image_table.sql` — Flyway migration for image table
- `src/main/java/com/davidneto/homepage/entity/Image.java` — JPA entity
- `src/main/java/com/davidneto/homepage/entity/OwnerType.java` — Enum for BLOG_POST / STATIC_PAGE
- `src/main/java/com/davidneto/homepage/repository/ImageRepository.java` — Spring Data repository
- `src/main/java/com/davidneto/homepage/service/ImageService.java` — Upload, list, delete logic
- `src/main/java/com/davidneto/homepage/controller/ImageApiController.java` — REST endpoints for image CRUD
- `src/test/java/com/davidneto/homepage/service/ImageServiceTest.java` — Unit tests
- `src/test/java/com/davidneto/homepage/controller/ImageApiControllerTest.java` — Controller tests
- `src/main/resources/static/js/image-gallery.js` — Gallery UI JavaScript

### Modified Files
- `Caddyfile` — Switch to HTTPS with `tls internal`
- `src/main/resources/application.yml` — Add multipart max file size
- `src/test/resources/application.yml` — Add multipart max file size for tests
- `src/main/resources/templates/admin/post-editor.html` — Add image gallery section
- `src/main/resources/templates/admin/page-editor.html` — Add image gallery section
- `src/main/resources/static/css/terminal.css` — Add image gallery styles
- `src/main/java/com/davidneto/homepage/service/BlogPostService.java` — Cascade delete images
- `src/main/java/com/davidneto/homepage/service/StaticPageService.java` — Cascade delete images
- `src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java` — Test cascade deletion
- `src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java` — Test cascade deletion
- `src/main/java/com/davidneto/homepage/config/SecurityConfig.java` — Expose CSRF token as cookie for JS API requests

---

### Task 1: HTTPS — Update Caddyfile

**Files:**
- Modify: `Caddyfile`

- [ ] **Step 1: Replace Caddyfile contents**

Replace the entire file with:

```
:443 {
    tls internal
    reverse_proxy app:8080
}

:80 {
    redir https://{host}{uri} permanent
}
```

- [ ] **Step 2: Commit**

```bash
git add Caddyfile
git commit -m "feat: switch Caddy to HTTPS with self-signed cert"
```

---

### Task 2: Flyway Migration — Image Table

**Files:**
- Create: `src/main/resources/db/migration/V2__add_image_table.sql`

- [ ] **Step 1: Create migration file**

```sql
CREATE TABLE image (
    id BIGSERIAL PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_image_owner ON image (owner_type, owner_id);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V2__add_image_table.sql
git commit -m "feat: add Flyway migration for image table"
```

---

### Task 3: OwnerType Enum and Image Entity

**Files:**
- Create: `src/main/java/com/davidneto/homepage/entity/OwnerType.java`
- Create: `src/main/java/com/davidneto/homepage/entity/Image.java`

- [ ] **Step 1: Create OwnerType enum**

```java
package com.davidneto.homepage.entity;

public enum OwnerType {
    BLOG_POST,
    STATIC_PAGE
}
```

- [ ] **Step 2: Create Image entity**

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "image")
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "stored_name", nullable = false)
    private String storedName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/entity/OwnerType.java src/main/java/com/davidneto/homepage/entity/Image.java
git commit -m "feat: add Image entity and OwnerType enum"
```

---

### Task 4: Image Repository

**Files:**
- Create: `src/main/java/com/davidneto/homepage/repository/ImageRepository.java`

- [ ] **Step 1: Create repository**

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(OwnerType ownerType, Long ownerId);

    void deleteByOwnerTypeAndOwnerId(OwnerType ownerType, Long ownerId);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/davidneto/homepage/repository/ImageRepository.java
git commit -m "feat: add ImageRepository"
```

---

### Task 5: ImageService — Write Tests

**Files:**
- Create: `src/test/java/com/davidneto/homepage/service/ImageServiceTest.java`

- [ ] **Step 1: Write failing tests for ImageService**

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.repository.ImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageRepository imageRepository;

    private ImageService imageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        imageService = new ImageService(imageRepository, tempDir.toString());
    }

    @Test
    void upload_savesFileAndRecord() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2, 3});
        Image saved = new Image();
        saved.setId(1L);
        when(imageRepository.save(any())).thenReturn(saved);

        Image result = imageService.upload(OwnerType.BLOG_POST, 1L, file);

        assertThat(result.getId()).isEqualTo(1L);
        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(captor.capture());
        Image captured = captor.getValue();
        assertThat(captured.getFilename()).isEqualTo("photo.png");
        assertThat(captured.getOwnerType()).isEqualTo(OwnerType.BLOG_POST);
        assertThat(captured.getOwnerId()).isEqualTo(1L);
        assertThat(captured.getContentType()).isEqualTo("image/png");
        assertThat(captured.getSize()).isEqualTo(3);
        // Verify file was written to disk
        Path ownerDir = tempDir.resolve("images/BLOG_POST/1");
        assertThat(Files.list(ownerDir).count()).isEqualTo(1);
    }

    @Test
    void upload_rejectsUnsupportedFormat() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> imageService.upload(OwnerType.BLOG_POST, 1L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image format");
    }

    @Test
    void listByOwner_returnsImages() {
        List<Image> images = List.of(new Image());
        when(imageRepository.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                OwnerType.BLOG_POST, 1L)).thenReturn(images);

        List<Image> result = imageService.listByOwner(OwnerType.BLOG_POST, 1L);

        assertThat(result).hasSize(1);
    }

    @Test
    void delete_removesFileAndRecord() throws IOException {
        Image image = new Image();
        image.setId(1L);
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        image.setStoredName("abc-123.png");
        when(imageRepository.findById(1L)).thenReturn(Optional.of(image));

        // Create the file on disk so we can verify deletion
        Path imageDir = tempDir.resolve("images/BLOG_POST/1");
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("abc-123.png"), new byte[]{1});

        imageService.delete(1L);

        verify(imageRepository).delete(image);
        assertThat(Files.exists(imageDir.resolve("abc-123.png"))).isFalse();
    }

    @Test
    void deleteAllByOwner_removesDirectoryAndRecords() throws IOException {
        // Create files on disk
        Path imageDir = tempDir.resolve("images/BLOG_POST/1");
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("img1.png"), new byte[]{1});
        Files.write(imageDir.resolve("img2.png"), new byte[]{2});

        imageService.deleteAllByOwner(OwnerType.BLOG_POST, 1L);

        verify(imageRepository).deleteByOwnerTypeAndOwnerId(OwnerType.BLOG_POST, 1L);
        assertThat(Files.exists(imageDir)).isFalse();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest=ImageServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: Compilation failure — `ImageService` class does not exist.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/davidneto/homepage/service/ImageServiceTest.java
git commit -m "test: add ImageService unit tests"
```

---

### Task 6: ImageService — Implementation

**Files:**
- Create: `src/main/java/com/davidneto/homepage/service/ImageService.java`

- [ ] **Step 1: Implement ImageService**

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private final ImageRepository imageRepository;
    private final String uploadDir;

    public ImageService(ImageRepository imageRepository,
                        @Value("${app.upload-dir}") String uploadDir) {
        this.imageRepository = imageRepository;
        this.uploadDir = uploadDir;
    }

    public Image upload(OwnerType ownerType, Long ownerId, MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String ext = getExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Unsupported image format. Allowed: jpg, jpeg, png, gif, webp");
        }

        String storedName = UUID.randomUUID() + ext;
        Path dir = Paths.get(uploadDir, "images", ownerType.name(), ownerId.toString());
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(storedName).toFile());

        Image image = new Image();
        image.setOwnerType(ownerType);
        image.setOwnerId(ownerId);
        image.setFilename(originalFilename);
        image.setStoredName(storedName);
        image.setContentType(file.getContentType());
        image.setSize(file.getSize());
        return imageRepository.save(image);
    }

    public List<Image> listByOwner(OwnerType ownerType, Long ownerId) {
        return imageRepository.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(ownerType, ownerId);
    }

    @Transactional
    public void delete(Long id) {
        Image image = imageRepository.findById(id).orElseThrow();
        Path filePath = Paths.get(uploadDir, "images",
                image.getOwnerType().name(), image.getOwnerId().toString(), image.getStoredName());
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            // Log but don't fail — DB record removal is more important
        }
        imageRepository.delete(image);
    }

    @Transactional
    public void deleteAllByOwner(OwnerType ownerType, Long ownerId) {
        imageRepository.deleteByOwnerTypeAndOwnerId(ownerType, ownerId);
        Path dir = Paths.get(uploadDir, "images", ownerType.name(), ownerId.toString());
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.delete(path); } catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            // Log but don't fail
        }
    }

    public String getImageUrl(Image image) {
        return "/uploads/images/" + image.getOwnerType().name() + "/"
                + image.getOwnerId() + "/" + image.getStoredName();
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest=ImageServiceTest`

Expected: All 5 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/ImageService.java
git commit -m "feat: implement ImageService with upload, list, delete"
```

---

### Task 7: Configure Multipart File Size

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`

- [ ] **Step 1: Add multipart config to application.yml**

Add under the `spring:` section, after the existing `flyway:` block:

```yaml
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
```

- [ ] **Step 2: Add same config to test application.yml**

Add under the `spring:` section, after the existing `flyway:` block:

```yaml
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml
git commit -m "feat: configure 5MB max upload size for image uploads"
```

---

### Task 8: SecurityConfig — CSRF Cookie for JavaScript API

The image API uses multipart uploads from JavaScript. Spring Security's default CSRF uses a session-based token. To make the CSRF token accessible from JS, expose it as a cookie that the JS can read and send back as a header.

**Files:**
- Modify: `src/main/java/com/davidneto/homepage/config/SecurityConfig.java`

- [ ] **Step 1: Update SecurityConfig to expose CSRF token as cookie**

Add to the `filterChain` method, after the `.logout()` block and before `return http.build();`:

```java
            .csrf(csrf -> csrf
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler())
            );
```

This makes the CSRF token available as the `XSRF-TOKEN` cookie, which the JavaScript can read and send back as the `X-XSRF-TOKEN` header. The existing form-based CSRF (hidden fields in Thymeleaf) continues to work because `CsrfTokenRequestAttributeHandler` supports both header and parameter-based tokens.

- [ ] **Step 2: Verify existing tests still pass**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest=AdminControllerTest`

Expected: All tests PASS (existing `.with(csrf())` in tests still works with cookie-based tokens).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/config/SecurityConfig.java
git commit -m "feat: configure CSRF cookie for JavaScript image API requests"
```

---

### Task 9: ImageApiController — Write Tests

**Files:**
- Create: `src/test/java/com/davidneto/homepage/controller/ImageApiControllerTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.config.SecurityConfig;
import com.davidneto.homepage.entity.Image;
import com.davidneto.homepage.entity.OwnerType;
import com.davidneto.homepage.service.ImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ImageApiController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.admin.username=admin",
        "app.admin.password=testpass",
        "app.upload-dir=/tmp/test-uploads"
})
class ImageApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageService imageService;

    @Test
    void upload_requiresAuthentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_returnsImageJson() throws Exception {
        Image image = new Image();
        image.setId(1L);
        image.setFilename("test.png");
        image.setStoredName("abc-123.png");
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        when(imageService.upload(eq(OwnerType.BLOG_POST), eq(1L), any())).thenReturn(image);
        when(imageService.getImageUrl(image)).thenReturn("/uploads/images/BLOG_POST/1/abc-123.png");

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.filename").value("test.png"))
                .andExpect(jsonPath("$.url").value("/uploads/images/BLOG_POST/1/abc-123.png"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returnsImagesForOwner() throws Exception {
        Image image = new Image();
        image.setId(1L);
        image.setFilename("photo.jpg");
        image.setStoredName("uuid.jpg");
        image.setOwnerType(OwnerType.BLOG_POST);
        image.setOwnerId(1L);
        when(imageService.listByOwner(OwnerType.BLOG_POST, 1L)).thenReturn(List.of(image));
        when(imageService.getImageUrl(image)).thenReturn("/uploads/images/BLOG_POST/1/uuid.jpg");

        mockMvc.perform(get("/admin/api/images")
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].filename").value("photo.jpg"))
                .andExpect(jsonPath("$[0].url").value("/uploads/images/BLOG_POST/1/uuid.jpg"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_removesImageAndReturnsOk() throws Exception {
        mockMvc.perform(delete("/admin/api/images/1").with(csrf()))
                .andExpect(status().isOk());

        verify(imageService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_rejectsUnsupportedFormat() throws Exception {
        when(imageService.upload(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unsupported image format"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", new byte[]{1});

        mockMvc.perform(multipart("/admin/api/images")
                        .file(file)
                        .param("ownerType", "BLOG_POST")
                        .param("ownerId", "1")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unsupported image format"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest=ImageApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: Compilation failure — `ImageApiController` class does not exist.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/davidneto/homepage/controller/ImageApiControllerTest.java
git commit -m "test: add ImageApiController tests"
```

---

### Task 10: ImageApiController — Implementation

**Files:**
- Create: `src/main/java/com/davidneto/homepage/controller/ImageApiController.java`

- [ ] **Step 1: Implement ImageApiController**

```java
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
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest=ImageApiControllerTest`

Expected: All 5 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/controller/ImageApiController.java
git commit -m "feat: implement image upload/list/delete REST API"
```

---

### Task 11: Cascade Deletion — Write Tests

**Files:**
- Modify: `src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java`
- Modify: `src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java`

- [ ] **Step 1: Add cascade deletion test to BlogPostServiceTest**

Add these imports at the top:

```java
import com.davidneto.homepage.entity.OwnerType;
```

Add a new mock field:

```java
@Mock
private ImageService imageService;
```

Remove the `@InjectMocks` annotation from `blogPostService` and change to manual construction in `setUp()`:

```java
private BlogPostService blogPostService;

@BeforeEach
void setUp() {
    blogPostService = new BlogPostService(blogPostRepository, imageService);
    post = new BlogPost();
    post.setId(1L);
    post.setTitle("Test Post");
    post.setSlug("test-post");
    post.setContent("# Hello");
    post.setPublished(false);
}
```

Add the test:

```java
@Test
void delete_cascadesImageDeletion() {
    when(blogPostRepository.findById(1L)).thenReturn(Optional.of(post));

    blogPostService.delete(1L);

    verify(imageService).deleteAllByOwner(OwnerType.BLOG_POST, 1L);
    verify(blogPostRepository).delete(post);
}
```

- [ ] **Step 2: Add cascade deletion test to StaticPageServiceTest**

Read the existing `StaticPageServiceTest.java` first. Then add matching imports and mock field:

```java
import com.davidneto.homepage.entity.OwnerType;
```

```java
@Mock
private ImageService imageService;
```

Change to manual construction in `setUp()`:

```java
private StaticPageService staticPageService;

@BeforeEach
void setUp() {
    staticPageService = new StaticPageService(staticPageRepository, imageService);
    page = new StaticPage();
    page.setId(1L);
    page.setTitle("Test Page");
    page.setSlug("test-page");
    page.setContent("# Hello");
    page.setPublished(false);
}
```

Add the test:

```java
@Test
void delete_cascadesImageDeletion() {
    when(staticPageRepository.findById(1L)).thenReturn(Optional.of(page));

    staticPageService.delete(1L);

    verify(imageService).deleteAllByOwner(OwnerType.STATIC_PAGE, 1L);
    verify(staticPageRepository).delete(page);
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest="BlogPostServiceTest,StaticPageServiceTest"`

Expected: FAIL — `BlogPostService` and `StaticPageService` constructors don't accept `ImageService` yet.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java
git commit -m "test: add cascade image deletion tests for post and page services"
```

---

### Task 12: Cascade Deletion — Implementation

**Files:**
- Modify: `src/main/java/com/davidneto/homepage/service/BlogPostService.java`
- Modify: `src/main/java/com/davidneto/homepage/service/StaticPageService.java`

- [ ] **Step 1: Update BlogPostService to cascade delete images**

Add import:

```java
import com.davidneto.homepage.entity.OwnerType;
```

Add `ImageService` as a constructor parameter:

```java
private final BlogPostRepository blogPostRepository;
private final ImageService imageService;

public BlogPostService(BlogPostRepository blogPostRepository, ImageService imageService) {
    this.blogPostRepository = blogPostRepository;
    this.imageService = imageService;
}
```

Update the `delete` method:

```java
@Transactional
public void delete(Long id) {
    BlogPost post = blogPostRepository.findById(id).orElseThrow();
    imageService.deleteAllByOwner(OwnerType.BLOG_POST, id);
    blogPostRepository.delete(post);
}
```

- [ ] **Step 2: Update StaticPageService to cascade delete images**

Add import:

```java
import com.davidneto.homepage.entity.OwnerType;
```

Add `ImageService` as a constructor parameter:

```java
private final StaticPageRepository staticPageRepository;
private final ImageService imageService;

public StaticPageService(StaticPageRepository staticPageRepository, ImageService imageService) {
    this.staticPageRepository = staticPageRepository;
    this.imageService = imageService;
}
```

Update the `delete` method:

```java
@Transactional
public void delete(Long id) {
    StaticPage page = staticPageRepository.findById(id).orElseThrow();
    imageService.deleteAllByOwner(OwnerType.STATIC_PAGE, id);
    staticPageRepository.delete(page);
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `cd .worktrees/homepage-impl && ./mvnw test -pl . -Dtest="BlogPostServiceTest,StaticPageServiceTest"`

Expected: All tests PASS.

- [ ] **Step 4: Run all tests to check nothing is broken**

Run: `cd .worktrees/homepage-impl && ./mvnw test`

Expected: All tests PASS. The `AdminControllerTest` may need an additional `@MockitoBean` for `ImageService` since the controller's services now depend on it — check and fix if needed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/BlogPostService.java src/main/java/com/davidneto/homepage/service/StaticPageService.java
git commit -m "feat: cascade delete images when deleting posts or pages"
```

---

### Task 13: Image Gallery CSS

**Files:**
- Modify: `src/main/resources/static/css/terminal.css`

- [ ] **Step 1: Add image gallery styles at the end of terminal.css, before the EasyMDE overrides section**

Insert before the `/* === EasyMDE Dark Theme Overrides === */` comment:

```css
/* === Image Gallery === */
.image-gallery-section {
    margin-top: 16px;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 16px;
}

.image-gallery-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
}

.image-gallery-title {
    color: var(--text-muted);
    font-size: 11px;
    text-transform: uppercase;
}

.image-upload-btn {
    color: var(--accent);
    font-size: 12px;
    border: 1px solid var(--accent);
    padding: 4px 12px;
    border-radius: 3px;
    background: none;
    font-family: inherit;
    cursor: pointer;
}

.image-upload-btn:hover {
    background: rgba(0, 255, 0, 0.1);
}

.image-gallery-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 8px;
}

.image-gallery-item {
    position: relative;
    border: 1px solid var(--border);
    border-radius: 3px;
    overflow: hidden;
    cursor: pointer;
    aspect-ratio: 1;
}

.image-gallery-item img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
}

.image-gallery-item:hover {
    border-color: var(--accent);
}

.image-gallery-item .delete-btn {
    position: absolute;
    top: 2px;
    right: 2px;
    color: #ff4444;
    background: rgba(10, 10, 10, 0.8);
    border: none;
    font-size: 14px;
    cursor: pointer;
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 2px;
    opacity: 0;
    transition: opacity 0.2s;
}

.image-gallery-item:hover .delete-btn {
    opacity: 1;
}

.image-gallery-empty {
    color: var(--text-muted);
    font-size: 12px;
    text-align: center;
    padding: 16px;
}

.image-copied-toast {
    position: fixed;
    bottom: 24px;
    right: 24px;
    background: var(--accent);
    color: var(--bg);
    padding: 8px 16px;
    border-radius: 4px;
    font-size: 13px;
    font-family: inherit;
    font-weight: bold;
    opacity: 0;
    transition: opacity 0.3s;
    pointer-events: none;
    z-index: 1000;
}

.image-copied-toast.show {
    opacity: 1;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/terminal.css
git commit -m "feat: add image gallery CSS styles"
```

---

### Task 14: Image Gallery JavaScript

**Files:**
- Create: `src/main/resources/static/js/image-gallery.js`

- [ ] **Step 1: Create image-gallery.js**

All DOM manipulation uses safe methods (`createElement`, `textContent`, `appendChild`). No `innerHTML` usage.

```javascript
document.addEventListener('DOMContentLoaded', function() {
    var gallerySection = document.getElementById('image-gallery-section');
    if (!gallerySection) return;

    var ownerType = gallerySection.dataset.ownerType;
    var ownerId = gallerySection.dataset.ownerId;
    var grid = document.getElementById('image-gallery-grid');
    var uploadBtn = document.getElementById('image-upload-btn');
    var fileInput = document.getElementById('image-file-input');
    var toast = document.getElementById('image-copied-toast');

    function getCsrfToken() {
        var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    function clearChildren(element) {
        while (element.firstChild) {
            element.removeChild(element.firstChild);
        }
    }

    function loadImages() {
        fetch('/admin/api/images?ownerType=' + encodeURIComponent(ownerType) + '&ownerId=' + encodeURIComponent(ownerId))
            .then(function(res) { return res.json(); })
            .then(function(images) {
                clearChildren(grid);
                if (images.length === 0) {
                    var empty = document.createElement('div');
                    empty.className = 'image-gallery-empty';
                    empty.textContent = 'No images uploaded yet';
                    grid.appendChild(empty);
                    return;
                }
                images.forEach(function(img) {
                    var item = document.createElement('div');
                    item.className = 'image-gallery-item';

                    var imgEl = document.createElement('img');
                    imgEl.src = img.url;
                    imgEl.alt = img.filename;
                    imgEl.title = 'Click to copy Markdown';
                    item.appendChild(imgEl);

                    var delBtn = document.createElement('button');
                    delBtn.className = 'delete-btn';
                    delBtn.textContent = '\u00D7';
                    delBtn.title = 'Delete image';
                    delBtn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        if (confirm('Delete this image?')) {
                            deleteImage(img.id);
                        }
                    });
                    item.appendChild(delBtn);

                    item.addEventListener('click', function() {
                        var markdown = '![' + img.filename + '](' + img.url + ')';
                        navigator.clipboard.writeText(markdown).then(function() {
                            showToast('Copied!');
                        });
                    });

                    grid.appendChild(item);
                });
            });
    }

    function deleteImage(id) {
        fetch('/admin/api/images/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() }
        }).then(function() {
            loadImages();
        });
    }

    function showToast(message) {
        toast.textContent = message;
        toast.classList.add('show');
        setTimeout(function() {
            toast.classList.remove('show');
        }, 1500);
    }

    uploadBtn.addEventListener('click', function() {
        fileInput.click();
    });

    fileInput.addEventListener('change', function() {
        if (!fileInput.files.length) return;
        var formData = new FormData();
        formData.append('file', fileInput.files[0]);
        formData.append('ownerType', ownerType);
        formData.append('ownerId', ownerId);

        fetch('/admin/api/images', {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() },
            body: formData
        })
        .then(function(res) {
            if (!res.ok) return res.json().then(function(data) { throw new Error(data.error); });
            return res.json();
        })
        .then(function() {
            loadImages();
            fileInput.value = '';
        })
        .catch(function(err) {
            alert(err.message || 'Upload failed');
            fileInput.value = '';
        });
    });

    loadImages();
});
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/image-gallery.js
git commit -m "feat: add image gallery JavaScript for upload, list, copy, delete"
```

---

### Task 15: Update Editor Templates

**Files:**
- Modify: `src/main/resources/templates/admin/post-editor.html`
- Modify: `src/main/resources/templates/admin/page-editor.html`

- [ ] **Step 1: Add image gallery section to post-editor.html**

After the closing `</form>` tag (line 51) and before the delete `<div>` (line 53), add:

```html
        <div th:if="${post.id != null}" id="image-gallery-section"
             th:data-owner-type="BLOG_POST" th:data-owner-id="${post.id}"
             class="image-gallery-section" style="margin-top: 16px;">
            <div class="image-gallery-header">
                <span class="image-gallery-title">Images</span>
                <button type="button" id="image-upload-btn" class="image-upload-btn">upload image</button>
                <input type="file" id="image-file-input" accept=".jpg,.jpeg,.png,.gif,.webp" style="display:none;">
            </div>
            <div id="image-gallery-grid" class="image-gallery-grid"></div>
        </div>
        <div id="image-copied-toast" class="image-copied-toast"></div>
```

Add the image-gallery.js script after the admin.js script tag at the bottom:

```html
    <script th:src="@{/js/image-gallery.js}"></script>
```

- [ ] **Step 2: Add image gallery section to page-editor.html**

After the closing `</form>` tag (line 41) and before the delete `<div>` (line 43), add:

```html
        <div th:if="${page.id != null}" id="image-gallery-section"
             th:data-owner-type="STATIC_PAGE" th:data-owner-id="${page.id}"
             class="image-gallery-section" style="margin-top: 16px;">
            <div class="image-gallery-header">
                <span class="image-gallery-title">Images</span>
                <button type="button" id="image-upload-btn" class="image-upload-btn">upload image</button>
                <input type="file" id="image-file-input" accept=".jpg,.jpeg,.png,.gif,.webp" style="display:none;">
            </div>
            <div id="image-gallery-grid" class="image-gallery-grid"></div>
        </div>
        <div id="image-copied-toast" class="image-copied-toast"></div>
```

Add the image-gallery.js script after the admin.js script tag:

```html
    <script th:src="@{/js/image-gallery.js}"></script>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/admin/post-editor.html src/main/resources/templates/admin/page-editor.html
git commit -m "feat: add image gallery UI to post and page editors"
```

---

### Task 16: Run Full Test Suite

- [ ] **Step 1: Run all tests**

Run: `cd .worktrees/homepage-impl && ./mvnw test`

Expected: All tests PASS. If `AdminControllerTest` fails because `BlogPostService` or `StaticPageService` now need `ImageService` injected, add `@MockitoBean private ImageService imageService;` to `AdminControllerTest`.

- [ ] **Step 2: Fix any failures**

If `AdminControllerTest` needs the `ImageService` mock, add this field to the test class:

```java
@MockitoBean
private ImageService imageService;
```

- [ ] **Step 3: Re-run tests after fixes**

Run: `cd .worktrees/homepage-impl && ./mvnw test`

Expected: All tests PASS.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix: add ImageService mock to AdminControllerTest"
```

---

### Task 17: Manual Verification Checklist

- [ ] **Step 1: Build the Docker image**

Run: `cd .worktrees/homepage-impl && docker compose build`

Expected: Build succeeds.

- [ ] **Step 2: Start the application**

Run: `cd .worktrees/homepage-impl && docker compose up -d`

Expected: All 3 containers start. Access `https://localhost` (accept self-signed cert warning). Verify HTTP `http://localhost` redirects to HTTPS.

- [ ] **Step 3: Test image upload flow**

1. Log in at `https://localhost/admin/login`
2. Create a new blog post, save it as draft
3. Verify the image gallery section appears after save
4. Upload an image via the "upload image" button
5. Verify the thumbnail appears in the gallery
6. Click the thumbnail — verify `![filename](url)` is copied to clipboard
7. Delete the image — verify it disappears
8. Repeat for a static page

- [ ] **Step 4: Test cascade deletion**

1. Upload images to a blog post
2. Delete the blog post
3. Verify images are cleaned up (check `docker exec app ls /app/uploads/images/`)

- [ ] **Step 5: Stop the application**

Run: `cd .worktrees/homepage-impl && docker compose down`
