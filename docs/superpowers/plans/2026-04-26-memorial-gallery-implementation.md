# Memorial Gallery (`/mae`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a private, login-walled memorial photo/video gallery at `/mae` with browser uploads, a WebDAV drop folder, year/month archive grouping by EXIF date, and a one-time bulk importer for ~2200 existing photos.

**Architecture:** New `com.davidneto.homepage.gallery` package, fully isolated from existing modules. Single `GalleryIngestService` orchestrates the pipeline (sniff → dedupe → metadata → derivatives → atomic finalize). DB is source of truth; files stored under UUID-prefixed paths. Separate Spring Security filter chain for `/mae/**` accepting either family or admin password. WebDAV drop folder is a second Milton filter registration with its own credential.

**Tech Stack:** Spring Boot 3.4.4, Java 21, PostgreSQL + Flyway, Thymeleaf, Spring Security, Milton WebDAV (existing), `metadata-extractor` (EXIF), `Thumbnailator` (image resize), `ffmpeg`/`ffprobe` (video). Tests use H2 + `@SpringBootTest`/`@DataJpaTest`/`@WebMvcTest`.

**Source spec:** `docs/superpowers/specs/2026-04-26-memorial-gallery-design.md`.

**Repo conventions** (from `CLAUDE.md` and `AGENTS.md`):
- Build: `./mvnw clean compile`
- Tests: `./mvnw test`; single test: `./mvnw test -Dtest=ClassName`
- Migrations: new Flyway file in `src/main/resources/db/migration/`
- Commits: NEVER add `Co-Authored-By` lines

**Parallelization hint for the dispatcher:** Phases 4 and 5 depend only on Phase 1. Phases 7 and 8 depend only on Phase 3. Phase 6 depends on Phase 5. Tasks within Phase 2 are mutually independent (after Task 4). Use this graph to run tasks in parallel where possible.

---

## Phase 1 — Foundation (deps, config, schema)

### Task 1: Add Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add `metadata-extractor`, `thumbnailator`, and test-scope `commons-imaging` to `pom.xml`**

Insert these `<dependency>` entries inside the `<dependencies>` block (after the existing `owasp-java-html-sanitizer` block, before `spring-boot-starter-test`):

```xml
        <dependency>
            <groupId>com.drewnoakes</groupId>
            <artifactId>metadata-extractor</artifactId>
            <version>2.19.0</version>
        </dependency>
        <dependency>
            <groupId>net.coobird</groupId>
            <artifactId>thumbnailator</artifactId>
            <version>0.4.20</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-imaging</artifactId>
            <version>1.0.0-alpha5</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Verify build still compiles**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add metadata-extractor, thumbnailator, commons-imaging deps for gallery"
```

---

### Task 2: GalleryProperties + application config

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/config/GalleryProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `.env.example`

- [ ] **Step 1: Create `GalleryProperties.java`**

```java
package com.davidneto.homepage.gallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.gallery")
public class GalleryProperties {

    private String rootDir = "./gallery";
    private Drop drop = new Drop();

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public Drop getDrop() { return drop; }
    public void setDrop(Drop drop) { this.drop = drop; }

    public static class Drop {
        private String username = "mae-drop";
        private String password = "";
        private int scanIntervalSeconds = 30;
        private int stableAfterSeconds = 10;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getScanIntervalSeconds() { return scanIntervalSeconds; }
        public void setScanIntervalSeconds(int v) { this.scanIntervalSeconds = v; }
        public int getStableAfterSeconds() { return stableAfterSeconds; }
        public void setStableAfterSeconds(int v) { this.stableAfterSeconds = v; }
    }
}
```

- [ ] **Step 2: Create `MaeProperties.java` for the gallery login + title**

Create `src/main/java/com/davidneto/homepage/gallery/config/MaeProperties.java`:

```java
package com.davidneto.homepage.gallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mae")
public class MaeProperties {

    private String password = "";
    private String title = "In memory of";

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
```

- [ ] **Step 3: Register both properties classes**

Modify `src/main/java/com/davidneto/homepage/HomepageApplication.java`. Add `@EnableConfigurationProperties({GalleryProperties.class, MaeProperties.class})` next to `@SpringBootApplication`. Show the resulting file head:

```java
package com.davidneto.homepage;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.gallery.config.MaeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({GalleryProperties.class, MaeProperties.class})
@EnableScheduling
public class HomepageApplication {
    public static void main(String[] args) {
        SpringApplication.run(HomepageApplication.class, args);
    }
}
```

(The `@EnableScheduling` is needed for `WebDavDropFolderScanner` in Task 24.)

- [ ] **Step 4: Update `src/main/resources/application.yml`**

Replace the existing `spring.servlet.multipart` block and append the new `app.mae` and `app.gallery` blocks. Final file:

```yaml
server:
  forward-headers-strategy: NATIVE

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${POSTGRES_DB:homepage}
    username: ${POSTGRES_USER:homepage}
    password: ${POSTGRES_PASSWORD:homepage}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB

app:
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin}
  upload-dir: ${UPLOAD_DIR:./uploads}
  webdav:
    root-dir: ${WEBDAV_ROOT_DIR:./webdav}
  mae:
    password: ${MAE_PASSWORD:}
    title: ${MAE_TITLE:In memory of}
  gallery:
    root-dir: ${GALLERY_ROOT_DIR:./gallery}
    drop:
      username: ${WEBDAV_DROP_USERNAME:mae-drop}
      password: ${WEBDAV_DROP_PASSWORD:}
      scan-interval-seconds: 30
      stable-after-seconds: 10
  rate-limit:
    ip-max-failures: 5
    ip-window-seconds: 60
    user-lockout-threshold: 5
    user-lockout-short-seconds: 300
    user-lockout-long-threshold: 10
    user-lockout-long-seconds: 1800
    max-entries: 10000
    sweep-interval-seconds: 600
```

- [ ] **Step 5: Update `src/test/resources/application-test.yml`**

Append `app.mae` and `app.gallery` blocks (test-only values):

```yaml
app:
  admin:
    username: admin
    password: admin
  upload-dir: ./build/test-uploads
  webdav:
    root-dir: ./build/test-webdav
  mae:
    password: testfamily
    title: Test Memorial
  gallery:
    root-dir: ./build/test-gallery
    drop:
      username: mae-drop
      password: testdrop
      scan-interval-seconds: 1
      stable-after-seconds: 1
  rate-limit:
    ip-max-failures: 5
    ip-window-seconds: 60
    user-lockout-threshold: 5
    user-lockout-short-seconds: 300
    user-lockout-long-threshold: 10
    user-lockout-long-seconds: 1800
    max-entries: 10000
    sweep-interval-seconds: 600
```

(Replace the existing `app:` block — keep the structure identical, just add the new sections.)

- [ ] **Step 6: Update `.env.example`**

Final content:

```
POSTGRES_DB=homepage
POSTGRES_USER=homepage
POSTGRES_PASSWORD=changeme
ADMIN_USERNAME=admin
ADMIN_PASSWORD=changeme
MAE_PASSWORD=changeme
MAE_TITLE=In memory of <name>
GALLERY_ROOT_DIR=/app/gallery
WEBDAV_DROP_USERNAME=mae-drop
WEBDAV_DROP_PASSWORD=changeme
```

- [ ] **Step 7: Verify the app still starts**

Run: `./mvnw test -Dtest=HomepageApplicationTests`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/config/ \
        src/main/java/com/davidneto/homepage/HomepageApplication.java \
        src/main/resources/application.yml \
        src/test/resources/application-test.yml \
        .env.example
git commit -m "feat(gallery): add GalleryProperties, MaeProperties, multipart limits"
```

---

### Task 3: Database migration V4 + entity + repository

**Files:**
- Create: `src/main/resources/db/migration/V4__add_gallery.sql`
- Create: `src/main/java/com/davidneto/homepage/gallery/entity/MediaKind.java`
- Create: `src/main/java/com/davidneto/homepage/gallery/entity/GalleryItem.java`
- Create: `src/main/java/com/davidneto/homepage/gallery/repository/GalleryItemRepository.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/repository/GalleryItemRepositoryTest.java`

- [ ] **Step 1: Write the failing repository test**

Create `src/test/java/com/davidneto/homepage/gallery/repository/GalleryItemRepositoryTest.java`:

```java
package com.davidneto.homepage.gallery.repository;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class GalleryItemRepositoryTest {

    @Autowired
    GalleryItemRepository repo;

    private GalleryItem make(String hash, int year, int month, LocalDateTime uploaded) {
        GalleryItem g = new GalleryItem();
        g.setMediaKind(MediaKind.PHOTO);
        g.setStorageKey(UUID.randomUUID());
        g.setOriginalFilename("a.jpg");
        g.setContentType("image/jpeg");
        g.setSizeBytes(1234);
        g.setContentHash(hash);
        g.setBucketYear(year);
        g.setBucketMonth(month);
        g.setBucketSource("EXIF");
        g.setUploadedAt(uploaded);
        return g;
    }

    @Test
    void findByContentHash_returnsExistingItem() {
        repo.save(make("aaaa".repeat(16), 2020, 5, LocalDateTime.now()));
        assertThat(repo.findByContentHash("aaaa".repeat(16))).isPresent();
        assertThat(repo.findByContentHash("missing".repeat(9) + "a")).isEmpty();
    }

    @Test
    void findByBucket_returnsItemsInThatMonth() {
        repo.save(make("h1".repeat(32), 2020, 5, LocalDateTime.now()));
        repo.save(make("h2".repeat(32), 2020, 5, LocalDateTime.now()));
        repo.save(make("h3".repeat(32), 2021, 5, LocalDateTime.now()));

        var items = repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(2020, 5);
        assertThat(items).hasSize(2);
    }

    @Test
    void findRecent_returnsItemsByUploadedAtDesc() {
        repo.save(make("r1".repeat(32), 2020, 5, LocalDateTime.of(2020, 1, 1, 0, 0)));
        repo.save(make("r2".repeat(32), 2020, 5, LocalDateTime.of(2024, 1, 1, 0, 0)));

        var page = repo.findAllByOrderByUploadedAtDesc(PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getContentHash()).isEqualTo("r2".repeat(32));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=GalleryItemRepositoryTest`
Expected: COMPILE FAIL — `MediaKind`, `GalleryItem`, `GalleryItemRepository` not found.

- [ ] **Step 3: Create the Flyway migration**

Create `src/main/resources/db/migration/V4__add_gallery.sql`:

```sql
CREATE TABLE gallery_item (
    id                  BIGSERIAL    PRIMARY KEY,
    media_kind          VARCHAR(10)  NOT NULL,
    storage_key         UUID         NOT NULL UNIQUE,
    original_filename   VARCHAR(512) NOT NULL,
    content_type        VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    content_hash        CHAR(64)     NOT NULL UNIQUE,
    width               INTEGER,
    height              INTEGER,
    duration_seconds    INTEGER,
    taken_at            TIMESTAMP,
    bucket_year         INTEGER      NOT NULL,
    bucket_month        INTEGER      NOT NULL,
    bucket_source       VARCHAR(10)  NOT NULL,
    uploaded_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    uploader_name       VARCHAR(100),
    caption             TEXT,
    caption_updated_at  TIMESTAMP,
    caption_updated_by  VARCHAR(100)
);

CREATE INDEX idx_gallery_bucket ON gallery_item (bucket_year DESC, bucket_month DESC);
CREATE INDEX idx_gallery_recent ON gallery_item (uploaded_at DESC);
CREATE INDEX idx_gallery_hash   ON gallery_item (content_hash);
```

- [ ] **Step 4: Create `MediaKind.java`**

```java
package com.davidneto.homepage.gallery.entity;

public enum MediaKind { PHOTO, VIDEO }
```

- [ ] **Step 5: Create `GalleryItem.java`**

```java
package com.davidneto.homepage.gallery.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "gallery_item")
public class GalleryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", nullable = false, length = 10)
    private MediaKind mediaKind;

    @Column(name = "storage_key", nullable = false, unique = true)
    private UUID storageKey;

    @Column(name = "original_filename", nullable = false, length = 512)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, unique = true, length = 64)
    private String contentHash;

    private Integer width;
    private Integer height;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    @Column(name = "bucket_year", nullable = false)
    private int bucketYear;

    @Column(name = "bucket_month", nullable = false)
    private int bucketMonth;

    @Column(name = "bucket_source", nullable = false, length = 10)
    private String bucketSource;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "uploader_name", length = 100)
    private String uploaderName;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "caption_updated_at")
    private LocalDateTime captionUpdatedAt;

    @Column(name = "caption_updated_by", length = 100)
    private String captionUpdatedBy;

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public MediaKind getMediaKind() { return mediaKind; }
    public void setMediaKind(MediaKind v) { this.mediaKind = v; }
    public UUID getStorageKey() { return storageKey; }
    public void setStorageKey(UUID v) { this.storageKey = v; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String v) { this.originalFilename = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { this.contentHash = v; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer v) { this.width = v; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer v) { this.height = v; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer v) { this.durationSeconds = v; }
    public LocalDateTime getTakenAt() { return takenAt; }
    public void setTakenAt(LocalDateTime v) { this.takenAt = v; }
    public int getBucketYear() { return bucketYear; }
    public void setBucketYear(int v) { this.bucketYear = v; }
    public int getBucketMonth() { return bucketMonth; }
    public void setBucketMonth(int v) { this.bucketMonth = v; }
    public String getBucketSource() { return bucketSource; }
    public void setBucketSource(String v) { this.bucketSource = v; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime v) { this.uploadedAt = v; }
    public String getUploaderName() { return uploaderName; }
    public void setUploaderName(String v) { this.uploaderName = v; }
    public String getCaption() { return caption; }
    public void setCaption(String v) { this.caption = v; }
    public LocalDateTime getCaptionUpdatedAt() { return captionUpdatedAt; }
    public void setCaptionUpdatedAt(LocalDateTime v) { this.captionUpdatedAt = v; }
    public String getCaptionUpdatedBy() { return captionUpdatedBy; }
    public void setCaptionUpdatedBy(String v) { this.captionUpdatedBy = v; }
}
```

- [ ] **Step 6: Create `GalleryItemRepository.java`**

```java
package com.davidneto.homepage.gallery.repository;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GalleryItemRepository extends JpaRepository<GalleryItem, Long> {

    Optional<GalleryItem> findByContentHash(String contentHash);

    List<GalleryItem> findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(
            int bucketYear, int bucketMonth);

    Page<GalleryItem> findAllByOrderByUploadedAtDesc(Pageable pageable);

    @Query("select distinct g.bucketYear from GalleryItem g order by g.bucketYear desc")
    List<Integer> findDistinctYearsDesc();

    @Query("""
           select g.bucketMonth as month, count(g) as itemCount
             from GalleryItem g
            where g.bucketYear = :year
         group by g.bucketMonth
         order by g.bucketMonth desc
           """)
    List<MonthSummary> findMonthSummaries(int year);

    interface MonthSummary {
        Integer getMonth();
        Long getItemCount();
    }
}
```

- [ ] **Step 7: Run the repository test**

Run: `./mvnw test -Dtest=GalleryItemRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V4__add_gallery.sql \
        src/main/java/com/davidneto/homepage/gallery/entity/ \
        src/main/java/com/davidneto/homepage/gallery/repository/ \
        src/test/java/com/davidneto/homepage/gallery/
git commit -m "feat(gallery): add gallery_item table, entity, repository"
```

---

## Phase 2 — Storage + extraction primitives

(Tasks 4–8 are independent of each other once Task 1 is done; the dispatcher may run them in parallel.)

### Task 4: GalleryStorage service

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/GalleryStorage.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/GalleryStorageTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GalleryStorageTest {

    @TempDir Path tmp;
    GalleryStorage storage;

    @BeforeEach
    void setUp() {
        GalleryProperties props = new GalleryProperties();
        props.setRootDir(tmp.toString());
        storage = new GalleryStorage(props);
        storage.init();
    }

    @Test
    void init_createsAllSubdirs() {
        assertThat(Files.isDirectory(tmp.resolve("originals"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("thumbs"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("display"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_tmp"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_drop"))).isTrue();
        assertThat(Files.isDirectory(tmp.resolve("_drop/_failed"))).isTrue();
    }

    @Test
    void originalPath_usesFirstTwoHexCharsAsSubdir() {
        UUID key = UUID.fromString("ab345678-1234-1234-1234-123456789abc");
        Path p = storage.originalPath(key, "jpg");
        assertThat(p).isEqualTo(tmp.resolve("originals/ab/ab345678-1234-1234-1234-123456789abc.jpg"));
    }

    @Test
    void thumbPath_alwaysJpg() {
        UUID key = UUID.fromString("cd000000-0000-0000-0000-000000000000");
        assertThat(storage.thumbPath(key))
                .isEqualTo(tmp.resolve("thumbs/cd/cd000000-0000-0000-0000-000000000000.jpg"));
    }

    @Test
    void newTempFile_returnsUniquePathInTmpDir() {
        Path a = storage.newTempFile();
        Path b = storage.newTempFile();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.getParent()).isEqualTo(tmp.resolve("_tmp"));
        assertThat(a.getFileName().toString()).endsWith(".part");
    }

    @Test
    void deleteAll_removesEveryDerivativeForAKey() throws Exception {
        UUID key = UUID.randomUUID();
        Files.createDirectories(storage.originalPath(key, "jpg").getParent());
        Files.write(storage.originalPath(key, "jpg"), new byte[]{1});
        Files.createDirectories(storage.thumbPath(key).getParent());
        Files.write(storage.thumbPath(key), new byte[]{1});
        Files.createDirectories(storage.displayPath(key).getParent());
        Files.write(storage.displayPath(key), new byte[]{1});

        storage.deleteAll(key, "jpg");

        assertThat(Files.exists(storage.originalPath(key, "jpg"))).isFalse();
        assertThat(Files.exists(storage.thumbPath(key))).isFalse();
        assertThat(Files.exists(storage.displayPath(key))).isFalse();
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL — class missing)**

Run: `./mvnw test -Dtest=GalleryStorageTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `GalleryStorage`**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class GalleryStorage {

    private final Path root;

    public GalleryStorage(GalleryProperties props) {
        this.root = Path.of(props.getRootDir()).toAbsolutePath();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root.resolve("originals"));
            Files.createDirectories(root.resolve("thumbs"));
            Files.createDirectories(root.resolve("display"));
            Files.createDirectories(root.resolve("_tmp"));
            Files.createDirectories(root.resolve("_drop"));
            Files.createDirectories(root.resolve("_drop/_failed"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() { return root; }
    public Path dropDir() { return root.resolve("_drop"); }
    public Path failedDir() { return root.resolve("_drop/_failed"); }

    public Path originalPath(UUID key, String ext) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("originals").resolve(shard).resolve(key + "." + ext);
    }

    public Path thumbPath(UUID key) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("thumbs").resolve(shard).resolve(key + ".jpg");
    }

    public Path displayPath(UUID key) {
        String shard = key.toString().substring(0, 2);
        return root.resolve("display").resolve(shard).resolve(key + ".jpg");
    }

    public Path newTempFile() {
        return root.resolve("_tmp").resolve(UUID.randomUUID() + ".part");
    }

    public void ensureParentDirs(Path p) {
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteAll(UUID key, String ext) {
        try {
            Files.deleteIfExists(originalPath(key, ext));
            Files.deleteIfExists(thumbPath(key));
            Files.deleteIfExists(displayPath(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=GalleryStorageTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/GalleryStorage.java \
        src/test/java/com/davidneto/homepage/gallery/service/GalleryStorageTest.java
git commit -m "feat(gallery): add GalleryStorage with UUID-sharded paths"
```

---

### Task 5: ExifExtractor

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/ExifExtractor.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/ExifExtractorTest.java`

The test uses `commons-imaging` to write a JPEG with a known EXIF `DateTimeOriginal` tag, then asserts the extractor reads it back.

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExifExtractorTest {

    @TempDir Path tmp;
    ExifExtractor extractor = new ExifExtractor();

    private byte[] tinyJpeg() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] withExifDate(byte[] jpeg, String exifDate) throws Exception {
        TiffOutputSet outputSet = new TiffOutputSet();
        var exifDir = outputSet.getOrCreateExifDirectory();
        exifDir.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(jpeg, out, outputSet);
        return out.toByteArray();
    }

    @Test
    void readsDateTimeOriginalFromExif() throws Exception {
        byte[] jpeg = withExifDate(tinyJpeg(), "2010:06:15 12:30:00");
        Path file = tmp.resolve("with-exif.jpg");
        Files.write(file, jpeg);

        ExifExtractor.Result r = extractor.extract(file);
        assertThat(r.takenAt()).isEqualTo(LocalDateTime.of(2010, 6, 15, 12, 30, 0));
        assertThat(r.width()).isEqualTo(8);
        assertThat(r.height()).isEqualTo(8);
    }

    @Test
    void returnsEmptyTakenAtForJpegWithoutExif() throws Exception {
        byte[] jpeg = tinyJpeg();
        Path file = tmp.resolve("no-exif.jpg");
        Files.write(file, jpeg);

        ExifExtractor.Result r = extractor.extract(file);
        assertThat(r.takenAt()).isNull();
        assertThat(r.width()).isEqualTo(8);
        assertThat(r.height()).isEqualTo(8);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=ExifExtractorTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `ExifExtractor`**

```java
package com.davidneto.homepage.gallery.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
public class ExifExtractor {

    public record Result(LocalDateTime takenAt, Integer width, Integer height, Integer orientation) {}

    public Result extract(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return extract(in);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }

    public Result extract(InputStream in) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(in);

            LocalDateTime taken = null;
            ExifSubIFDDirectory sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                Date d = sub.getDateOriginal(java.util.TimeZone.getDefault());
                if (d != null) {
                    taken = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                }
            }

            Integer width = null, height = null;
            JpegDirectory jpeg = md.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpeg != null) {
                if (jpeg.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) width = jpeg.getInt(JpegDirectory.TAG_IMAGE_WIDTH);
                if (jpeg.containsTag(JpegDirectory.TAG_IMAGE_HEIGHT)) height = jpeg.getInt(JpegDirectory.TAG_IMAGE_HEIGHT);
            }

            Integer orientation = null;
            ExifIFD0Directory ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }

            return new Result(taken, width, height, orientation);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=ExifExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/ExifExtractor.java \
        src/test/java/com/davidneto/homepage/gallery/service/ExifExtractorTest.java
git commit -m "feat(gallery): add ExifExtractor for DateTimeOriginal + dimensions"
```

---

### Task 6: VideoMetadataExtractor (ffprobe wrapper)

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/VideoMetadataExtractor.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/VideoMetadataExtractorTest.java`

This wraps `ffprobe`. The unit test fakes ffprobe output via dependency injection (a `ProcessRunner` interface). A separate integration test (Task 9 ingest IT) exercises real ffprobe when present.

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMetadataExtractorTest {

    @Test
    void parsesFfprobeJson() {
        String json = """
                {
                  "streams": [
                    {"codec_type": "video", "width": 1920, "height": 1080}
                  ],
                  "format": {
                    "duration": "12.345",
                    "tags": { "creation_time": "2018-04-12T15:30:00.000000Z" }
                  }
                }
                """;
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> json);

        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isEqualTo(1920);
        assertThat(r.height()).isEqualTo(1080);
        assertThat(r.durationSeconds()).isEqualTo(12);
        assertThat(r.takenAt()).isEqualTo(LocalDateTime.of(2018, 4, 12, 15, 30, 0));
    }

    @Test
    void returnsNullsWhenJsonMissingFields() {
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> "{}");
        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isNull();
        assertThat(r.height()).isNull();
        assertThat(r.durationSeconds()).isNull();
        assertThat(r.takenAt()).isNull();
    }

    @Test
    void emptyOutputReturnsAllNulls() {
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> "");
        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isNull();
        assertThat(r.takenAt()).isNull();
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL — class missing)**

Run: `./mvnw test -Dtest=VideoMetadataExtractorTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `VideoMetadataExtractor`**

```java
package com.davidneto.homepage.gallery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class VideoMetadataExtractor {

    public interface ProcessRunner {
        String run(List<String> command) throws Exception;
    }

    public record Result(LocalDateTime takenAt, Integer width, Integer height, Integer durationSeconds) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProcessRunner runner;

    public VideoMetadataExtractor() {
        this(VideoMetadataExtractor::runFfprobe);
    }

    public VideoMetadataExtractor(ProcessRunner runner) {
        this.runner = runner;
    }

    public Result extract(Path file) {
        String json;
        try {
            json = runner.run(List.of(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", file.toString()));
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
        if (json == null || json.isBlank()) return new Result(null, null, null, null);
        try {
            JsonNode root = MAPPER.readTree(json);
            Integer width = null, height = null;
            for (JsonNode stream : root.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())) {
                    width = stream.path("width").isMissingNode() ? null : stream.path("width").asInt();
                    height = stream.path("height").isMissingNode() ? null : stream.path("height").asInt();
                    break;
                }
            }
            Integer dur = null;
            JsonNode durNode = root.path("format").path("duration");
            if (durNode.isTextual()) {
                try { dur = (int) Math.floor(Double.parseDouble(durNode.asText())); } catch (NumberFormatException ignored) {}
            }
            LocalDateTime taken = null;
            JsonNode created = root.path("format").path("tags").path("creation_time");
            if (created.isTextual()) {
                try {
                    taken = LocalDateTime.ofInstant(
                            DateTimeFormatter.ISO_DATE_TIME.parse(created.asText(), java.time.Instant::from),
                            ZoneOffset.UTC);
                } catch (Exception ignored) {}
            }
            return new Result(taken, width, height, dur);
        } catch (Exception e) {
            return new Result(null, null, null, null);
        }
    }

    private static String runFfprobe(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String out = br.lines().collect(Collectors.joining("\n"));
            p.waitFor();
            if (p.exitValue() != 0) throw new RuntimeException("ffprobe exit " + p.exitValue());
            return out;
        }
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=VideoMetadataExtractorTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/VideoMetadataExtractor.java \
        src/test/java/com/davidneto/homepage/gallery/service/VideoMetadataExtractorTest.java
git commit -m "feat(gallery): add VideoMetadataExtractor (ffprobe wrapper) with injectable runner"
```

---

### Task 7: ThumbnailGenerator

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/ThumbnailGenerator.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/ThumbnailGeneratorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailGeneratorTest {

    @TempDir Path tmp;
    ThumbnailGenerator gen = new ThumbnailGenerator();

    private Path writeJpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Path f = tmp.resolve("src-" + w + "x" + h + ".jpg");
        ImageIO.write(img, "jpg", f.toFile());
        return f;
    }

    @Test
    void thumb400_writesOutputJpegWithLongestSide400() throws Exception {
        Path src = writeJpeg(2000, 1000);
        Path out = tmp.resolve("out-thumb.jpg");
        gen.writeThumbnail(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(400);
    }

    @Test
    void display1600_writesOutputJpegWithLongestSide1600() throws Exception {
        Path src = writeJpeg(3200, 1600);
        Path out = tmp.resolve("out-display.jpg");
        gen.writeDisplay(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(1600);
    }

    @Test
    void doesNotUpscale_smallSourceCopiedNearAsIs() throws Exception {
        Path src = writeJpeg(100, 80);
        Path out = tmp.resolve("out-small.jpg");
        gen.writeThumbnail(src, out, null);
        BufferedImage result = ImageIO.read(out.toFile());
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(80);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=ThumbnailGeneratorTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `ThumbnailGenerator`**

```java
package com.davidneto.homepage.gallery.service;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ThumbnailGenerator {

    private static final int THUMB_MAX = 400;
    private static final int DISPLAY_MAX = 1600;
    private static final float JPEG_THUMB_QUALITY = 0.80f;
    private static final float JPEG_DISPLAY_QUALITY = 0.85f;

    public void writeThumbnail(Path src, Path out, Integer exifOrientation) throws IOException {
        write(src, out, THUMB_MAX, JPEG_THUMB_QUALITY, exifOrientation);
    }

    public void writeDisplay(Path src, Path out, Integer exifOrientation) throws IOException {
        write(src, out, DISPLAY_MAX, JPEG_DISPLAY_QUALITY, exifOrientation);
    }

    private void write(Path src, Path out, int maxLongSide, float quality, Integer orientation) throws IOException {
        Files.createDirectories(out.getParent());
        var builder = Thumbnails.of(src.toFile())
                .size(maxLongSide, maxLongSide)
                .keepAspectRatio(true)
                .outputFormat("jpg")
                .outputQuality(quality);
        if (orientation != null) {
            switch (orientation) {
                case 3 -> builder.rotate(180);
                case 6 -> builder.rotate(90);
                case 8 -> builder.rotate(270);
                default -> { /* 1 (or unknown) — no rotation */ }
            }
        }
        builder.toFile(out.toFile());
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=ThumbnailGeneratorTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/ThumbnailGenerator.java \
        src/test/java/com/davidneto/homepage/gallery/service/ThumbnailGeneratorTest.java
git commit -m "feat(gallery): add ThumbnailGenerator (Thumbnailator) for thumb/display sizes"
```

---

### Task 8: VideoPosterGenerator

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/VideoPosterGenerator.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/VideoPosterGeneratorTest.java`

The test injects a fake `PosterRunner` that simulates ffmpeg by writing a known JPEG to the requested output path.

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VideoPosterGeneratorTest {

    @TempDir Path tmp;

    @Test
    void writesPosterByCallingFfmpegRunnerWithExpectedArgs() throws Exception {
        Path src = tmp.resolve("v.mp4");
        Files.write(src, new byte[]{0});
        Path out = tmp.resolve("poster.jpg");

        VideoPosterGenerator gen = new VideoPosterGenerator((args) -> {
            assertThat(args).contains("-ss", "00:00:01");
            assertThat(args).contains("-vframes", "1");
            assertThat(args).endsWith(out.toString());
            BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", out.toFile());
        });

        gen.writePoster(src, out);
        assertThat(Files.exists(out)).isTrue();
        BufferedImage img = ImageIO.read(out.toFile());
        assertThat(img.getWidth()).isEqualTo(640);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=VideoPosterGeneratorTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `VideoPosterGenerator`**

```java
package com.davidneto.homepage.gallery.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class VideoPosterGenerator {

    public interface PosterRunner {
        void run(List<String> args) throws Exception;
    }

    private final PosterRunner runner;

    public VideoPosterGenerator() {
        this(VideoPosterGenerator::runFfmpeg);
    }

    public VideoPosterGenerator(PosterRunner runner) {
        this.runner = runner;
    }

    public void writePoster(Path src, Path out) throws IOException {
        Files.createDirectories(out.getParent());
        try {
            runner.run(List.of(
                    "ffmpeg", "-y", "-ss", "00:00:01", "-i", src.toString(),
                    "-vframes", "1", "-vf", "scale=1600:-2", out.toString()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ffmpeg failed", e);
        }
    }

    private static void runFfmpeg(List<String> args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        p.waitFor();
        if (p.exitValue() != 0) throw new RuntimeException("ffmpeg exit " + p.exitValue());
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=VideoPosterGeneratorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/VideoPosterGenerator.java \
        src/test/java/com/davidneto/homepage/gallery/service/VideoPosterGeneratorTest.java
git commit -m "feat(gallery): add VideoPosterGenerator (ffmpeg wrapper) with injectable runner"
```

---

## Phase 3 — Ingest service

### Task 9: GalleryIngestService

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/GalleryIngestService.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/GalleryIngestServiceIT.java`

The ingest service is wired with real `GalleryStorage`, `ExifExtractor`, `ThumbnailGenerator`, and stub `VideoMetadataExtractor`/`VideoPosterGenerator` (so the test does not require ffmpeg). Photo path is exercised end-to-end. Video path is covered by a separate test that injects fake extractors.

- [ ] **Step 1: Write the failing integration test**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GalleryIngestServiceIT {

    @Autowired GalleryIngestService ingest;
    @Autowired GalleryItemRepository repo;
    @Autowired GalleryStorage storage;

    @BeforeEach
    void wipe() throws Exception {
        repo.deleteAll();
        // Best-effort cleanup of test-gallery dir
        if (Files.exists(storage.root())) {
            try (var s = Files.walk(storage.root())) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        storage.init();
    }

    private byte[] jpegWithExif(String exifDate) throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateExifDirectory()
                .add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, outputSet);
        return out.toByteArray();
    }

    @Test
    void photoIngest_createsRow_writesAllDerivatives_bucketsByExif() throws Exception {
        byte[] bytes = jpegWithExif("2010:06:15 12:30:00");

        GalleryIngestService.IngestResult r = ingest.ingest(
                new ByteArrayInputStream(bytes), "vacation.jpg", "image/jpeg", "Maria");

        assertThat(r.deduped()).isFalse();
        GalleryItem item = repo.findById(r.itemId()).orElseThrow();
        assertThat(item.getMediaKind()).isEqualTo(MediaKind.PHOTO);
        assertThat(item.getBucketYear()).isEqualTo(2010);
        assertThat(item.getBucketMonth()).isEqualTo(6);
        assertThat(item.getBucketSource()).isEqualTo("EXIF");
        assertThat(item.getUploaderName()).isEqualTo("Maria");
        assertThat(item.getContentHash()).hasSize(64);
        assertThat(Files.exists(storage.originalPath(item.getStorageKey(), "jpg"))).isTrue();
        assertThat(Files.exists(storage.thumbPath(item.getStorageKey()))).isTrue();
        assertThat(Files.exists(storage.displayPath(item.getStorageKey()))).isTrue();
    }

    @Test
    void photoIngest_withoutExif_bucketsByUploadTime_andSetsBucketSourceUpload() throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);

        var r = ingest.ingest(new ByteArrayInputStream(baos.toByteArray()),
                "phone.jpg", "image/jpeg", null);

        GalleryItem item = repo.findById(r.itemId()).orElseThrow();
        assertThat(item.getBucketSource()).isEqualTo("UPLOAD");
        assertThat(item.getTakenAt()).isNull();
        assertThat(item.getBucketYear()).isEqualTo(item.getUploadedAt().getYear());
        assertThat(item.getBucketMonth()).isEqualTo(item.getUploadedAt().getMonthValue());
    }

    @Test
    void duplicateIngest_isANoop_returnsDedupedTrue_andSameItemId() throws Exception {
        byte[] bytes = jpegWithExif("2018:01:01 08:00:00");

        var first = ingest.ingest(new ByteArrayInputStream(bytes), "a.jpg", "image/jpeg", "X");
        var second = ingest.ingest(new ByteArrayInputStream(bytes), "b.jpg", "image/jpeg", "Y");

        assertThat(first.deduped()).isFalse();
        assertThat(second.deduped()).isTrue();
        assertThat(second.itemId()).isEqualTo(first.itemId());
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void rejectsDisallowedMimeBySniffing() throws Exception {
        byte[] notAnImage = "this is plain text".getBytes();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ingest.ingest(new ByteArrayInputStream(notAnImage), "file.jpg", "image/jpeg", null)
        ).isInstanceOf(GalleryIngestService.UnsupportedMediaException.class);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL — service not present)**

Run: `./mvnw test -Dtest=GalleryIngestServiceIT`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `GalleryIngestService`**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class GalleryIngestService {

    public record IngestResult(long itemId, boolean deduped) {}

    public static class UnsupportedMediaException extends RuntimeException {
        public UnsupportedMediaException(String msg) { super(msg); }
    }

    private final GalleryStorage storage;
    private final GalleryItemRepository repo;
    private final ExifExtractor exif;
    private final VideoMetadataExtractor videoMeta;
    private final ThumbnailGenerator thumbs;
    private final VideoPosterGenerator posters;

    public GalleryIngestService(GalleryStorage storage,
                                GalleryItemRepository repo,
                                ExifExtractor exif,
                                VideoMetadataExtractor videoMeta,
                                ThumbnailGenerator thumbs,
                                VideoPosterGenerator posters) {
        this.storage = storage;
        this.repo = repo;
        this.exif = exif;
        this.videoMeta = videoMeta;
        this.thumbs = thumbs;
        this.posters = posters;
    }

    @Transactional
    public IngestResult ingest(InputStream src, String originalFilename,
                               String declaredContentType, String uploaderName) throws IOException {
        Path tmp = storage.newTempFile();
        storage.ensureParentDirs(tmp);

        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }

        long size = 0;
        try (InputStream in = src; OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
                size += n;
            }
        }
        String hash = HexFormat.of().formatHex(sha.digest());

        var existing = repo.findByContentHash(hash);
        if (existing.isPresent()) {
            Files.deleteIfExists(tmp);
            return new IngestResult(existing.get().getId(), true);
        }

        String sniffed = MediaTypeSniffer.sniff(tmp);
        if (sniffed == null) {
            Files.deleteIfExists(tmp);
            throw new UnsupportedMediaException("unsupported or unrecognized media type for " + originalFilename);
        }
        MediaKind kind = sniffed.startsWith("video/") ? MediaKind.VIDEO : MediaKind.PHOTO;
        String ext = MediaTypeSniffer.extensionFor(sniffed);

        UUID storageKey = UUID.randomUUID();
        LocalDateTime uploadedAt = LocalDateTime.now();

        Integer width = null, height = null, durationSeconds = null, orientation = null;
        LocalDateTime takenAt = null;

        if (kind == MediaKind.PHOTO) {
            ExifExtractor.Result r = exif.extract(tmp);
            takenAt = r.takenAt();
            width = r.width();
            height = r.height();
            orientation = r.orientation();
        } else {
            VideoMetadataExtractor.Result r = videoMeta.extract(tmp);
            takenAt = r.takenAt();
            width = r.width();
            height = r.height();
            durationSeconds = r.durationSeconds();
        }

        int bucketYear, bucketMonth;
        String bucketSource;
        if (takenAt != null) {
            bucketYear = takenAt.getYear();
            bucketMonth = takenAt.getMonthValue();
            bucketSource = "EXIF";
        } else {
            bucketYear = uploadedAt.getYear();
            bucketMonth = uploadedAt.getMonthValue();
            bucketSource = "UPLOAD";
        }

        Path original = storage.originalPath(storageKey, ext);
        Path thumb = storage.thumbPath(storageKey);
        Path display = storage.displayPath(storageKey);
        storage.ensureParentDirs(original);
        storage.ensureParentDirs(thumb);
        storage.ensureParentDirs(display);

        try {
            Files.move(tmp, original, StandardCopyOption.ATOMIC_MOVE);

            if (kind == MediaKind.PHOTO) {
                thumbs.writeDisplay(original, display, orientation);
                thumbs.writeThumbnail(original, thumb, orientation);
            } else {
                posters.writePoster(original, display);
                thumbs.writeThumbnail(display, thumb, null);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(original); } catch (Exception ignored) {}
            try { Files.deleteIfExists(thumb); } catch (Exception ignored) {}
            try { Files.deleteIfExists(display); } catch (Exception ignored) {}
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw new IOException("ingest derivative generation failed", e);
        }

        GalleryItem item = new GalleryItem();
        item.setMediaKind(kind);
        item.setStorageKey(storageKey);
        item.setOriginalFilename(originalFilename);
        item.setContentType(sniffed);
        item.setSizeBytes(size);
        item.setContentHash(hash);
        item.setWidth(width);
        item.setHeight(height);
        item.setDurationSeconds(durationSeconds);
        item.setTakenAt(takenAt);
        item.setBucketYear(bucketYear);
        item.setBucketMonth(bucketMonth);
        item.setBucketSource(bucketSource);
        item.setUploadedAt(uploadedAt);
        item.setUploaderName(uploaderName);
        repo.save(item);
        return new IngestResult(item.getId(), false);
    }
}
```

- [ ] **Step 4: Create `MediaTypeSniffer`**

Create `src/main/java/com/davidneto/homepage/gallery/service/MediaTypeSniffer.java`:

```java
package com.davidneto.homepage.gallery.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class MediaTypeSniffer {

    private MediaTypeSniffer() {}

    static String sniff(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(32);
            if (head.length < 4) return null;

            // JPEG: FF D8 FF
            if ((head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8 && (head[2] & 0xff) == 0xFF)
                return "image/jpeg";
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if ((head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G')
                return "image/png";
            // WebP: RIFF....WEBP
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P')
                return "image/webp";
            // HEIC: ftypheic / ftypheix / ftypmif1 (also covers HEIF variants)
            if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                String brand = new String(head, 8, 4);
                if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("heif"))
                    return "image/heic";
                if (brand.equals("isom") || brand.equals("mp42") || brand.equals("mp41") || brand.equals("avc1"))
                    return "video/mp4";
                if (brand.equals("qt  "))
                    return "video/quicktime";
            }
            return null;
        }
    }

    static String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/heic" -> "heic";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }
}
```

- [ ] **Step 5: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=GalleryIngestServiceIT`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/GalleryIngestService.java \
        src/main/java/com/davidneto/homepage/gallery/service/MediaTypeSniffer.java \
        src/test/java/com/davidneto/homepage/gallery/service/GalleryIngestServiceIT.java
git commit -m "feat(gallery): add GalleryIngestService with sniff/dedupe/derivatives/atomic finalize"
```

---

## Phase 4 — Security and auth

### Task 10: Order existing SecurityConfig + permitAll for `/gallery-drop/**`

**Files:**
- Modify: `src/main/java/com/davidneto/homepage/config/SecurityConfig.java`

- [ ] **Step 1: Add `@Order(2)` to the existing filter chain bean and add `/gallery-drop/**` to its permit-all + CSRF-ignore lists**

Modify the existing `filterChain` bean. Final method:

```java
    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http, LoginRateLimiter limiter) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/webdav/**").permitAll()
                .requestMatchers("/api/webdav/**").permitAll()
                .requestMatchers("/gallery-drop/**").permitAll()
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/admin/login")
                .successHandler(new RateLimitAuthenticationSuccessHandler(limiter))
                .failureHandler(new RateLimitAuthenticationFailureHandler(limiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/webdav/**", "/api/webdav/**", "/gallery-drop/**")
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .addFilterBefore(new LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
```

- [ ] **Step 2: Verify all existing tests still pass**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/config/SecurityConfig.java
git commit -m "chore(security): order existing chain at @Order(2) and permit /gallery-drop"
```

---

### Task 11: GallerySecurityConfig — dual-password login at `/mae/login`

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/security/GallerySecurityConfig.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/security/GallerySecurityIT.java`

The `/mae/login` form accepts a single `password` field. We compare it (constant-time, BCrypt-hashed at startup) against both `app.mae.password` and `app.admin.password`. The match grants `ROLE_GALLERY_CONTRIBUTOR` only, or both `ROLE_GALLERY_CONTRIBUTOR` and `ROLE_GALLERY_ADMIN`.

We implement this with a custom `AuthenticationProvider` that maps the submitted password to a fixed principal + roles.

- [ ] **Step 1: Write the failing security IT**

```java
package com.davidneto.homepage.gallery.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.SharedHttpSessionConfigurer.sharedHttpSession;

@SpringBootTest
@ActiveProfiles("test")
class GallerySecurityIT {

    @Autowired WebApplicationContext wac;
    @Autowired com.davidneto.homepage.security.LoginRateLimiter limiter;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        limiter.resetAllForTesting();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .apply(sharedHttpSession())
                .build();
    }

    @Test
    void unauthenticatedAccessTo_mae_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/mae"))
               .andExpect(status().is3xxRedirection())
               .andExpect(redirectedUrlPattern("**/mae/login"));
    }

    @Test
    void familyPassword_authenticatesWithGalleryContributorRole() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "testfamily").password("password", "testfamily"))
               .andExpect(authenticated().withRoles("GALLERY_CONTRIBUTOR"));
    }

    @Test
    void adminPassword_authenticatesWithBothRoles() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "admin").password("password", "admin"))
               .andExpect(authenticated().withRoles("GALLERY_CONTRIBUTOR", "GALLERY_ADMIN"));
    }

    @Test
    void wrongPassword_isUnauthenticated() throws Exception {
        mockMvc.perform(formLogin("/mae/login").user("password", "nope").password("password", "nope"))
               .andExpect(unauthenticated());
    }
}
```

(Spring Security's `formLogin()` test helper sends both a username and password param; here we configure the form so the username field is named `password` too — Spring's helper doesn't allow only-password forms, so we accept the redundant username for tests. The production form template will only show the password field.)

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=GallerySecurityIT`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `GallerySecurityConfig`**

```java
package com.davidneto.homepage.gallery.security;

import com.davidneto.homepage.gallery.config.MaeProperties;
import com.davidneto.homepage.security.LoginRateLimitFilter;
import com.davidneto.homepage.security.LoginRateLimiter;
import com.davidneto.homepage.security.RateLimitAuthenticationFailureHandler;
import com.davidneto.homepage.security.RateLimitAuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
public class GallerySecurityConfig {

    private final MaeProperties mae;
    private final String adminPasswordRaw;
    private final PasswordEncoder encoder;
    private final String maeHash;
    private final String adminHash;

    public GallerySecurityConfig(MaeProperties mae,
                                 @Value("${app.admin.password}") String adminPasswordRaw,
                                 PasswordEncoder encoder) {
        this.mae = mae;
        this.adminPasswordRaw = adminPasswordRaw;
        this.encoder = encoder;
        // Hash once at startup so we never have plaintext-versus-plaintext compare hot in memory.
        this.maeHash = mae.getPassword() == null || mae.getPassword().isBlank() ? null : encoder.encode(mae.getPassword());
        this.adminHash = encoder.encode(adminPasswordRaw);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain galleryFilterChain(HttpSecurity http, LoginRateLimiter limiter) throws Exception {
        http
            .securityMatcher("/mae/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/mae/login", "/mae/css/**", "/mae/js/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/mae/api/items/*").hasRole("GALLERY_ADMIN")
                .anyRequest().hasRole("GALLERY_CONTRIBUTOR")
            )
            .formLogin(form -> form
                .loginPage("/mae/login")
                .loginProcessingUrl("/mae/login")
                .usernameParameter("password")  // Spring requires both params; we ignore username server-side
                .passwordParameter("password")
                .defaultSuccessUrl("/mae", true)
                .failureUrl("/mae/login?error")
                .successHandler(new RateLimitAuthenticationSuccessHandler(limiter))
                .failureHandler(new RateLimitAuthenticationFailureHandler(limiter))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/mae/logout")
                .logoutSuccessUrl("/mae/login")
                .permitAll()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse())
            )
            .authenticationProvider(galleryAuthenticationProvider())
            .addFilterBefore(new LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter.class)
            .headers(h -> h.addHeaderWriter((req, resp) -> {
                if (req.getRequestURI().startsWith("/mae")) resp.setHeader("X-Robots-Tag", "noindex, nofollow");
            }));

        return http.build();
    }

    @Bean
    public AuthenticationProvider galleryAuthenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) throws AuthenticationException {
                String submitted = auth.getCredentials() == null ? "" : auth.getCredentials().toString();
                if (submitted.isEmpty()) throw new BadCredentialsException("empty");

                if (encoder.matches(submitted, adminHash)) {
                    return new UsernamePasswordAuthenticationToken("admin", null, List.of(
                            new SimpleGrantedAuthority("ROLE_GALLERY_CONTRIBUTOR"),
                            new SimpleGrantedAuthority("ROLE_GALLERY_ADMIN")));
                }
                if (maeHash != null && encoder.matches(submitted, maeHash)) {
                    return new UsernamePasswordAuthenticationToken("family", null, List.of(
                            new SimpleGrantedAuthority("ROLE_GALLERY_CONTRIBUTOR")));
                }
                throw new BadCredentialsException("bad password");
            }

            @Override
            public boolean supports(Class<?> auth) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(auth);
            }
        };
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=GallerySecurityIT`
Expected: PASS (4 tests).

- [ ] **Step 5: Verify the rest of the test suite still passes**

Run: `./mvnw test`
Expected: PASS for everything.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/security/GallerySecurityConfig.java \
        src/test/java/com/davidneto/homepage/gallery/security/GallerySecurityIT.java
git commit -m "feat(gallery): add /mae security chain with dual-password login"
```

---

## Phase 5 — JSON API, media serving, robots.txt

### Task 12: GalleryApiController — upload, patch caption, delete

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/controller/GalleryApiController.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/controller/GalleryApiControllerIT.java`

- [ ] **Step 1: Write the failing IT**

```java
package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.entity.MediaKind;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class GalleryApiControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GalleryItemRepository repo;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private byte[] tinyJpeg() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void upload_acceptsMultipleFilesAndAppliesSharedCaption() throws Exception {
        MockMultipartFile a = new MockMultipartFile("file", "a.jpg", "image/jpeg", tinyJpeg());
        MockMultipartFile b = new MockMultipartFile("file", "b.jpg", "image/jpeg", tinyJpeg());

        // a and b are byte-identical (same generator) so b should dedupe.
        mockMvc.perform(multipart("/mae/api/items").file(a).file(b)
                        .param("uploaderName", "Carlos")
                        .param("caption", "Christmas")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deduped").value(false))
                .andExpect(jsonPath("$[1].deduped").value(true));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void patchCaption_updatesItem() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(patch("/mae/api/items/" + item.getId())
                        .contentType("application/json")
                        .content("{\"caption\":\"Aunt Maria, 1985\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caption").value("Aunt Maria, 1985"));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void delete_forbiddenForContributor() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(delete("/mae/api/items/" + item.getId()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"GALLERY_CONTRIBUTOR", "GALLERY_ADMIN"})
    void delete_succeedsForAdmin() throws Exception {
        GalleryItem item = persistDummy();
        mockMvc.perform(delete("/mae/api/items/" + item.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private GalleryItem persistDummy() {
        GalleryItem item = new GalleryItem();
        item.setMediaKind(MediaKind.PHOTO);
        item.setStorageKey(UUID.randomUUID());
        item.setOriginalFilename("d.jpg");
        item.setContentType("image/jpeg");
        item.setSizeBytes(1);
        item.setContentHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        item.setBucketYear(2020);
        item.setBucketMonth(1);
        item.setBucketSource("UPLOAD");
        item.setUploadedAt(LocalDateTime.now());
        return repo.save(item);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL — controller missing)**

Run: `./mvnw test -Dtest=GalleryApiControllerIT`
Expected: 404 / class missing.

- [ ] **Step 3: Implement `GalleryApiController` and the supporting `GalleryItemService`**

Create `src/main/java/com/davidneto/homepage/gallery/service/GalleryItemService.java`:

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class GalleryItemService {

    private final GalleryItemRepository repo;
    private final GalleryStorage storage;

    public GalleryItemService(GalleryItemRepository repo, GalleryStorage storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public Optional<GalleryItem> find(long id) { return repo.findById(id); }

    @Transactional
    public GalleryItem updateCaption(long id, String caption, String editorName) {
        GalleryItem item = repo.findById(id).orElseThrow(NoSuchElementException::new);
        item.setCaption(caption);
        item.setCaptionUpdatedAt(LocalDateTime.now());
        item.setCaptionUpdatedBy(editorName == null || editorName.isBlank() ? null : editorName);
        return item;
    }

    @Transactional
    public void delete(long id) throws IOException {
        GalleryItem item = repo.findById(id).orElseThrow(NoSuchElementException::new);
        String ext = MediaTypeSniffer.extensionFor(item.getContentType());
        repo.delete(item);
        storage.deleteAll(item.getStorageKey(), ext);
    }
}
```

(Note: `MediaTypeSniffer` was created package-private in Task 9. Promote it to public for cross-package use here. Change the class declaration in `src/main/java/com/davidneto/homepage/gallery/service/MediaTypeSniffer.java` from `final class MediaTypeSniffer {` to `public final class MediaTypeSniffer {`, and change the `extensionFor` method modifier from `static` to `public static`. The `sniff` method may stay package-private.)

Create `src/main/java/com/davidneto/homepage/gallery/controller/GalleryApiController.java`:

```java
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
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=GalleryApiControllerIT`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/GalleryItemService.java \
        src/main/java/com/davidneto/homepage/gallery/service/MediaTypeSniffer.java \
        src/main/java/com/davidneto/homepage/gallery/controller/GalleryApiController.java \
        src/test/java/com/davidneto/homepage/gallery/controller/GalleryApiControllerIT.java
git commit -m "feat(gallery): add /mae/api/items upload, patch caption, delete endpoints"
```

---

### Task 13: Media-serving endpoints (thumb, display, original)

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/controller/GalleryMediaController.java`
- Test: extends `GalleryApiControllerIT`-style fixture; new test class.
- Test: `src/test/java/com/davidneto/homepage/gallery/controller/GalleryMediaControllerIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import com.davidneto.homepage.gallery.service.GalleryIngestService;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class GalleryMediaControllerIT {

    @Autowired WebApplicationContext wac;
    @Autowired GalleryItemRepository repo;
    @Autowired GalleryIngestService ingest;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        repo.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private long ingestSamplePhoto() throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, "2010:01:01 00:00:00");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        return ingest.ingest(new ByteArrayInputStream(out.toByteArray()), "x.jpg", "image/jpeg", null).itemId();
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void thumb_returnsJpegBytes_andCacheHeaders() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/thumb/" + id))
               .andExpect(status().isOk())
               .andExpect(content().contentType("image/jpeg"))
               .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    @Test
    @WithMockUser(roles = "GALLERY_CONTRIBUTOR")
    void original_returnsContentDispositionAttachment() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/original/" + id))
               .andExpect(status().isOk())
               .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void thumb_unauthenticated_redirectsToLogin() throws Exception {
        long id = ingestSamplePhoto();
        mockMvc.perform(get("/mae/media/thumb/" + id))
               .andExpect(status().is3xxRedirection());
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=GalleryMediaControllerIT`
Expected: FAIL.

- [ ] **Step 3: Implement `GalleryMediaController`**

```java
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
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=GalleryMediaControllerIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/controller/GalleryMediaController.java \
        src/test/java/com/davidneto/homepage/gallery/controller/GalleryMediaControllerIT.java
git commit -m "feat(gallery): add /mae/media/{thumb,display,original}/{id} endpoints"
```

---

### Task 14: robots.txt — disallow `/mae`

**Files:**
- Create: `src/main/resources/static/robots.txt`

- [ ] **Step 1: Write the file**

```
User-agent: *
Disallow: /mae
```

- [ ] **Step 2: Verify it serves**

Run: `./mvnw spring-boot:run` (in another shell), then `curl -s http://localhost:8080/robots.txt`. Expected output:

```
User-agent: *
Disallow: /mae
```

Stop the dev server.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/robots.txt
git commit -m "chore: add robots.txt disallowing /mae"
```

---

## Phase 6 — HTML pages, CSS, JS

These tasks have minimal automated tests; verification is rendering the page and clicking through. Templates are provided in full; do not paraphrase.

### Task 15: GalleryController — HTML page routes

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/controller/GalleryController.java`

- [ ] **Step 1: Implement `GalleryController`**

```java
package com.davidneto.homepage.gallery.controller;

import com.davidneto.homepage.gallery.config.MaeProperties;
import com.davidneto.homepage.gallery.entity.GalleryItem;
import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import com.davidneto.homepage.gallery.service.GalleryItemService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Month;
import java.util.*;

@Controller
@RequestMapping("/mae")
public class GalleryController {

    private final GalleryItemRepository repo;
    private final GalleryItemService items;
    private final MaeProperties props;

    public GalleryController(GalleryItemRepository repo, GalleryItemService items, MaeProperties props) {
        this.repo = repo;
        this.items = items;
        this.props = props;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("title", props.getTitle());
        return "mae/login";
    }

    @GetMapping({"", "/"})
    public String landing(Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("recent",
                repo.findAllByOrderByUploadedAtDesc(PageRequest.of(0, 12)).getContent());

        List<Integer> years = repo.findDistinctYearsDesc();
        Map<Integer, List<MonthEntry>> byYear = new LinkedHashMap<>();
        for (Integer y : years) {
            List<MonthEntry> months = new ArrayList<>();
            for (var s : repo.findMonthSummaries(y)) {
                List<GalleryItem> preview =
                        repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(y, s.getMonth())
                            .stream().limit(6).toList();
                months.add(new MonthEntry(s.getMonth(), s.getItemCount(), preview));
            }
            byYear.put(y, months);
        }
        model.addAttribute("byYear", byYear);
        return "mae/landing";
    }

    @GetMapping("/recent")
    public String recent(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("page", repo.findAllByOrderByUploadedAtDesc(PageRequest.of(page, 60)));
        return "mae/recent";
    }

    @GetMapping("/{year}/{month}")
    public String month(@PathVariable int year, @PathVariable int month, Model model) {
        model.addAttribute("title", props.getTitle());
        model.addAttribute("year", year);
        model.addAttribute("month", month);
        model.addAttribute("monthName", Month.of(month).name());
        model.addAttribute("items",
                repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(year, month));
        return "mae/month";
    }

    @GetMapping("/item/{id}")
    public String lightbox(@PathVariable long id, Model model) {
        GalleryItem item = items.find(id).orElseThrow();
        model.addAttribute("title", props.getTitle());
        model.addAttribute("item", item);

        List<GalleryItem> siblings = repo.findByBucketYearAndBucketMonthOrderByTakenAtAscUploadedAtAsc(
                item.getBucketYear(), item.getBucketMonth());
        int idx = -1;
        for (int i = 0; i < siblings.size(); i++) if (siblings.get(i).getId().equals(item.getId())) { idx = i; break; }
        model.addAttribute("prev", idx > 0 ? siblings.get(idx - 1) : null);
        model.addAttribute("next", idx >= 0 && idx < siblings.size() - 1 ? siblings.get(idx + 1) : null);
        return "mae/lightbox";
    }

    @GetMapping("/upload")
    public String upload(Model model) {
        model.addAttribute("title", props.getTitle());
        return "mae/upload";
    }

    public record MonthEntry(int month, long itemCount, List<GalleryItem> preview) {
        public String monthName() { return Month.of(month).name(); }
    }
}
```

- [ ] **Step 2: Compile to ensure it builds**

Run: `./mvnw clean compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/controller/GalleryController.java
git commit -m "feat(gallery): add HTML page controller for /mae routes"
```

---

### Task 16: mae.css

**Files:**
- Create: `src/main/resources/static/css/mae.css`

- [ ] **Step 1: Write the stylesheet**

(Full content provided as a single block — copy verbatim.)

```css
:root {
    --bg: #faf7f2;
    --fg: #2a2520;
    --muted: #7a716a;
    --accent: #8a5a3b;
    --card: #ffffff;
    --border: #e6dfd5;
}
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: var(--bg); color: var(--fg); font-family: Georgia, "Times New Roman", serif; }
a { color: var(--accent); text-decoration: none; }
a:hover { text-decoration: underline; }
.mae-header { padding: 2rem 1rem 1rem; text-align: center; border-bottom: 1px solid var(--border); }
.mae-header h1 { margin: 0 0 .25rem; font-weight: normal; font-size: 1.8rem; }
.mae-header .nav { margin-top: .75rem; font-size: .9rem; color: var(--muted); }
.mae-header .nav a { margin: 0 .5rem; }
.mae-main { max-width: 1200px; margin: 0 auto; padding: 1rem; }
.section-title { margin: 2rem 0 .75rem; font-weight: normal; font-size: 1.2rem; color: var(--muted); border-bottom: 1px dotted var(--border); padding-bottom: .25rem; }
.recent-strip { display: flex; gap: .5rem; overflow-x: auto; padding: .25rem 0 1rem; }
.recent-strip a { flex: 0 0 auto; }
.recent-strip img { height: 140px; width: auto; border-radius: 4px; display: block; }
.month-section { margin-bottom: 1.5rem; }
.month-section h3 { margin: .25rem 0 .5rem; font-weight: normal; }
.month-preview { display: grid; grid-template-columns: repeat(6, 1fr); gap: .25rem; }
.month-preview img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 3px; }
.year-block { margin-top: 2rem; }
.year-block h2 { font-weight: normal; font-size: 1.5rem; margin: 0 0 .5rem; color: var(--accent); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: .5rem; }
.grid a { display: block; }
.grid img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 3px; }
.grid .video-badge { position: absolute; top: 4px; right: 4px; background: rgba(0,0,0,.6); color: #fff; padding: 1px 6px; font-size: .7rem; border-radius: 2px; }
.grid .item { position: relative; }
.lightbox { max-width: 1200px; margin: 1rem auto; padding: 1rem; }
.lightbox .media { text-align: center; }
.lightbox img, .lightbox video { max-width: 100%; max-height: 80vh; }
.lightbox .meta { margin-top: 1rem; color: var(--muted); font-size: .9rem; }
.lightbox .caption { margin-top: 1rem; padding: 1rem; background: var(--card); border: 1px solid var(--border); border-radius: 4px; }
.lightbox .caption textarea { width: 100%; min-height: 80px; font-family: inherit; font-size: 1rem; padding: .5rem; }
.lightbox .actions { margin-top: 1rem; display: flex; gap: 1rem; justify-content: space-between; }
.lightbox .actions .danger { color: #a33; }
.login { max-width: 360px; margin: 4rem auto; padding: 2rem; background: var(--card); border: 1px solid var(--border); border-radius: 6px; text-align: center; }
.login input[type=password] { width: 100%; padding: .6rem; font-size: 1rem; margin: 1rem 0; border: 1px solid var(--border); border-radius: 4px; }
.login button { width: 100%; padding: .6rem; font-size: 1rem; background: var(--accent); color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.login .error { color: #a33; margin-top: 1rem; }
.upload { max-width: 800px; margin: 1rem auto; padding: 1rem; }
.dropzone { border: 2px dashed var(--border); border-radius: 8px; padding: 3rem; text-align: center; color: var(--muted); cursor: pointer; }
.dropzone.drag { background: #f0e8d8; border-color: var(--accent); color: var(--fg); }
.upload-fields { margin-top: 1rem; display: flex; flex-direction: column; gap: .5rem; }
.upload-fields input, .upload-fields textarea { padding: .6rem; font-size: 1rem; border: 1px solid var(--border); border-radius: 4px; font-family: inherit; }
.upload-list { margin-top: 1rem; }
.upload-row { display: flex; justify-content: space-between; gap: .5rem; padding: .5rem; border-bottom: 1px solid var(--border); }
.upload-row progress { flex: 1; }
.fab { position: fixed; right: 1.5rem; bottom: 1.5rem; padding: .75rem 1.25rem; background: var(--accent); color: #fff; border-radius: 999px; box-shadow: 0 2px 8px rgba(0,0,0,.2); }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/mae.css
git commit -m "feat(gallery): add mae.css stylesheet"
```

---

### Task 17: Templates — layout-dialect dep, layout, login, landing

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/templates/mae/layout.html`
- Create: `src/main/resources/templates/mae/login.html`
- Create: `src/main/resources/templates/mae/landing.html`

- [ ] **Step 1: Add `thymeleaf-layout-dialect` dependency to `pom.xml`**

Insert next to the existing thymeleaf entries (Spring Boot manages the version):

```xml
        <dependency>
            <groupId>nz.net.ultraq.thymeleaf</groupId>
            <artifactId>thymeleaf-layout-dialect</artifactId>
        </dependency>
```

- [ ] **Step 2: Create `src/main/resources/templates/mae/layout.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title th:text="${title}">Memorial</title>
    <link rel="stylesheet" th:href="@{/css/mae.css}">
</head>
<body>
<header class="mae-header">
    <h1 th:text="${title}">In memory of</h1>
    <nav class="nav">
        <a th:href="@{/mae}">Home</a>
        <a th:href="@{/mae/recent}">Recently added</a>
        <a th:href="@{/mae/upload}">Add photos</a>
        <form th:action="@{/mae/logout}" method="post" style="display:inline">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" style="background:none;border:none;color:inherit;cursor:pointer;font:inherit">Sign out</button>
        </form>
    </nav>
</header>
<main class="mae-main" layout:fragment="main">
    <p>placeholder</p>
</main>
<a class="fab" th:href="@{/mae/upload}">+ Add photos</a>
<script th:src="@{/js/mae.js}" defer></script>
</body>
</html>
```

- [ ] **Step 3: Create `src/main/resources/templates/mae/login.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title th:text="${title}">Memorial</title>
    <link rel="stylesheet" th:href="@{/css/mae.css}">
</head>
<body>
<div class="login">
    <h1 th:text="${title}">In memory of</h1>
    <form th:action="@{/mae/login}" method="post">
        <input type="password" name="password" placeholder="Password" autofocus required autocomplete="current-password"/>
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit">Enter</button>
    </form>
    <p class="error" th:if="${param.error}">Wrong password.</p>
</div>
</body>
</html>
```

(The Spring Security form-login filter posts both username and password from the same form field name — that's why `GallerySecurityConfig` set both `usernameParameter` and `passwordParameter` to `password`. Only one input is needed in the form.)

- [ ] **Step 4: Create `src/main/resources/templates/mae/landing.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head>
    <title th:text="${title}">Memorial</title>
</head>
<body>
<div layout:decorate="~{mae/layout}">
    <main layout:fragment="main">
        <h2 class="section-title">Recently added</h2>
        <div class="recent-strip">
            <a th:each="i : ${recent}" th:href="@{/mae/item/{id}(id=${i.id})}">
                <img th:src="@{/mae/media/thumb/{id}(id=${i.id})}" alt=""/>
            </a>
            <a th:href="@{/mae/recent}" style="align-self:center; padding:0 1rem;">see all →</a>
        </div>
        <div th:each="entry : ${byYear}" class="year-block">
            <h2 th:text="${entry.key}">2020</h2>
            <div th:each="m : ${entry.value}" class="month-section">
                <h3>
                    <a th:href="@{/mae/{y}/{m}(y=${entry.key},m=${m.month})}"
                       th:text="|${m.monthName()} (${m.itemCount()})|">January (12)</a>
                </h3>
                <div class="month-preview">
                    <a th:each="p : ${m.preview()}" th:href="@{/mae/item/{id}(id=${p.id})}">
                        <img th:src="@{/mae/media/thumb/{id}(id=${p.id})}" alt=""/>
                    </a>
                </div>
            </div>
        </div>
    </main>
</div>
</body>
</html>
```

- [ ] **Step 5: Manual smoke test**

Run: `./mvnw spring-boot:run` (with `MAE_PASSWORD=family` env var). In a browser:
- Visit `http://localhost:8080/mae` → should redirect to `/mae/login`.
- Enter `family` → should land on the gallery (empty if no items).

Stop the server.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/templates/mae/
git commit -m "feat(gallery): add layout, login, landing templates + layout-dialect dep"
```

---

### Task 18: Templates — month, recent, lightbox, upload

**Files:**
- Create: `src/main/resources/templates/mae/month.html`
- Create: `src/main/resources/templates/mae/recent.html`
- Create: `src/main/resources/templates/mae/lightbox.html`
- Create: `src/main/resources/templates/mae/upload.html`

- [ ] **Step 1: Create `month.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head><title th:text="${title}">Memorial</title></head>
<body>
<div layout:decorate="~{mae/layout}">
    <main layout:fragment="main">
        <h2 class="section-title" th:text="|${monthName} ${year}|">January 2020</h2>
        <div class="grid">
            <div th:each="i : ${items}" class="item">
                <a th:href="@{/mae/item/{id}(id=${i.id})}">
                    <img th:src="@{/mae/media/thumb/{id}(id=${i.id})}" alt=""/>
                </a>
                <span th:if="${i.mediaKind.name() == 'VIDEO'}" class="video-badge">VIDEO</span>
            </div>
        </div>
    </main>
</div>
</body>
</html>
```

- [ ] **Step 2: Create `recent.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head><title th:text="${title}">Memorial</title></head>
<body>
<div layout:decorate="~{mae/layout}">
    <main layout:fragment="main">
        <h2 class="section-title">Recently added</h2>
        <div class="grid">
            <div th:each="i : ${page.content}" class="item">
                <a th:href="@{/mae/item/{id}(id=${i.id})}">
                    <img th:src="@{/mae/media/thumb/{id}(id=${i.id})}" alt=""/>
                </a>
                <span th:if="${i.mediaKind.name() == 'VIDEO'}" class="video-badge">VIDEO</span>
            </div>
        </div>
        <div style="margin-top:1rem; text-align:center;">
            <a th:if="${page.hasPrevious()}" th:href="@{/mae/recent(page=${page.number - 1})}">← Newer</a>
            <span style="margin: 0 1rem; color: var(--muted);" th:text="|page ${page.number + 1} of ${page.totalPages}|"></span>
            <a th:if="${page.hasNext()}" th:href="@{/mae/recent(page=${page.number + 1})}">Older →</a>
        </div>
    </main>
</div>
</body>
</html>
```

- [ ] **Step 3: Create `lightbox.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      xmlns:sec="http://www.thymeleaf.org/extras/spring-security">
<head><title th:text="${title}">Memorial</title></head>
<body>
<div layout:decorate="~{mae/layout}">
    <main layout:fragment="main">
        <div class="lightbox" th:attr="data-item-id=${item.id}">
            <div class="media">
                <th:block th:if="${item.mediaKind.name() == 'PHOTO'}">
                    <img th:src="@{/mae/media/display/{id}(id=${item.id})}" alt=""/>
                </th:block>
                <th:block th:if="${item.mediaKind.name() == 'VIDEO'}">
                    <video controls preload="metadata"
                           th:poster="@{/mae/media/display/{id}(id=${item.id})}"
                           th:src="@{/mae/media/original/{id}(id=${item.id})}"></video>
                </th:block>
            </div>
            <div class="meta">
                <span th:if="${item.uploaderName != null}" th:text="|Added by ${item.uploaderName} · |"></span>
                <span th:text="|Uploaded ${#temporals.format(item.uploadedAt, 'yyyy-MM-dd')}|"></span>
                <span th:if="${item.takenAt != null}"
                      th:text="| · Taken ${#temporals.format(item.takenAt, 'yyyy-MM-dd')}|"></span>
                <span th:text="| · ${item.originalFilename}|"></span>
            </div>
            <div class="caption">
                <div class="caption-display" th:text="${item.caption ?: 'No caption yet — click edit to add one.'}"></div>
                <textarea class="caption-edit" style="display:none;" th:text="${item.caption}"></textarea>
                <div style="margin-top:.5rem;">
                    <button class="btn-edit-caption">Edit caption</button>
                    <button class="btn-save-caption" style="display:none;">Save</button>
                    <button class="btn-cancel-caption" style="display:none;">Cancel</button>
                </div>
            </div>
            <div class="actions">
                <div class="nav-links">
                    <a th:if="${prev != null}" class="nav-prev" th:href="@{/mae/item/{id}(id=${prev.id})}">← Previous</a>
                    <a th:if="${next != null}" class="nav-next" th:href="@{/mae/item/{id}(id=${next.id})}">Next →</a>
                </div>
                <div>
                    <a th:href="@{/mae/media/original/{id}(id=${item.id})}">Download original</a>
                    <button sec:authorize="hasRole('GALLERY_ADMIN')" class="danger btn-delete">Delete</button>
                </div>
            </div>
        </div>
    </main>
</div>
</body>
</html>
```

- [ ] **Step 4: Create `upload.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout">
<head><title th:text="${title}">Memorial</title></head>
<body>
<div layout:decorate="~{mae/layout}">
    <main layout:fragment="main">
        <div class="upload">
            <h2 class="section-title">Add photos and videos</h2>
            <div class="upload-fields">
                <input id="uploader-name" type="text" placeholder="Your name (optional)" autocomplete="name"/>
                <textarea id="batch-caption" placeholder="Caption for this batch (optional)"></textarea>
            </div>
            <div id="dropzone" class="dropzone">
                Drop photos/videos here or click to choose
                <input id="file-input" type="file" multiple accept="image/*,video/*" style="display:none;"/>
            </div>
            <div id="upload-list" class="upload-list"></div>
            <input type="hidden" id="csrf-token" th:value="${_csrf.token}"/>
            <input type="hidden" id="csrf-name" th:value="${_csrf.parameterName}"/>
        </div>
    </main>
</div>
</body>
</html>
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/mae/
git commit -m "feat(gallery): add month, recent, lightbox, upload templates"
```

---

### Task 19: mae.js — uploader, lightbox, caption editing

**Files:**
- Create: `src/main/resources/static/js/mae.js`

All DOM construction uses `document.createElement` and `textContent` — no `innerHTML` anywhere. The only externally-influenced strings are user-supplied filenames and uploader names; both are written via `textContent` so they cannot be interpreted as HTML.

- [ ] **Step 1: Write the script**

```javascript
(function () {
    'use strict';

    const dropzone = document.getElementById('dropzone');
    if (dropzone) initUploader();

    function initUploader() {
        const fileInput = document.getElementById('file-input');
        const list = document.getElementById('upload-list');
        const nameInput = document.getElementById('uploader-name');
        const captionInput = document.getElementById('batch-caption');
        const csrfToken = document.getElementById('csrf-token').value;
        const csrfName = document.getElementById('csrf-name').value;

        const cookieName = 'mae_contributor';
        const cookieMatch = document.cookie.split('; ').find(r => r.startsWith(cookieName + '='));
        if (cookieMatch) nameInput.value = decodeURIComponent(cookieMatch.split('=')[1]);
        nameInput.addEventListener('change', () => {
            const v = encodeURIComponent(nameInput.value || '');
            document.cookie = cookieName + '=' + v + '; max-age=' + (60 * 60 * 24 * 365) + '; path=/mae; secure; samesite=lax';
        });

        dropzone.addEventListener('click', () => fileInput.click());
        ['dragenter', 'dragover'].forEach(ev =>
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.add('drag'); }));
        ['dragleave', 'drop'].forEach(ev =>
            dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.remove('drag'); }));
        dropzone.addEventListener('drop', e => uploadAll(e.dataTransfer.files));
        fileInput.addEventListener('change', () => uploadAll(fileInput.files));

        let inflight = 0;
        const queue = [];
        const MAX = 3;

        function uploadAll(files) {
            for (const f of files) queue.push(f);
            pump();
        }

        function pump() {
            while (inflight < MAX && queue.length > 0) {
                inflight++;
                uploadOne(queue.shift()).finally(() => { inflight--; pump(); });
            }
        }

        function buildRow(filename) {
            const row = document.createElement('div');
            row.className = 'upload-row';
            const nameSpan = document.createElement('span');
            nameSpan.textContent = filename;
            const progress = document.createElement('progress');
            progress.max = 100; progress.value = 0;
            const status = document.createElement('span');
            status.className = 'status';
            row.appendChild(nameSpan);
            row.appendChild(progress);
            row.appendChild(status);
            return { row: row, progress: progress, status: status };
        }

        function setStatusLink(statusEl, href, text) {
            statusEl.textContent = '';
            const a = document.createElement('a');
            a.setAttribute('href', href);
            a.textContent = text;
            statusEl.appendChild(a);
        }

        function uploadOne(file) {
            const built = buildRow(file.name);
            list.appendChild(built.row);

            return new Promise(resolve => {
                const fd = new FormData();
                fd.append('file', file);
                if (nameInput.value) fd.append('uploaderName', nameInput.value);
                if (captionInput.value) fd.append('caption', captionInput.value);
                fd.append(csrfName, csrfToken);

                const xhr = new XMLHttpRequest();
                xhr.open('POST', '/mae/api/items');
                xhr.upload.onprogress = e => { if (e.lengthComputable) built.progress.value = (e.loaded / e.total) * 100; };
                xhr.onload = () => {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        let arr = [];
                        try { arr = JSON.parse(xhr.responseText); } catch (e) {}
                        const r = arr[0] || {};
                        if (r.error) {
                            built.status.textContent = '✗ ' + String(r.error);
                        } else if (r.deduped) {
                            setStatusLink(built.status, '/mae/item/' + Number(r.id), 'already in gallery');
                        } else {
                            setStatusLink(built.status, '/mae/item/' + Number(r.id), '✓ added');
                        }
                    } else {
                        built.status.textContent = '✗ ' + xhr.status;
                    }
                    resolve();
                };
                xhr.onerror = () => { built.status.textContent = '✗ network error'; resolve(); };
                xhr.send(fd);
            });
        }
    }

    const lightbox = document.querySelector('.lightbox[data-item-id]');
    if (lightbox) initLightbox();

    function initLightbox() {
        const id = lightbox.getAttribute('data-item-id');
        const display = lightbox.querySelector('.caption-display');
        const edit = lightbox.querySelector('.caption-edit');
        const editBtn = lightbox.querySelector('.btn-edit-caption');
        const saveBtn = lightbox.querySelector('.btn-save-caption');
        const cancelBtn = lightbox.querySelector('.btn-cancel-caption');
        const deleteBtn = lightbox.querySelector('.btn-delete');
        const csrfToken = getCookie('XSRF-TOKEN');

        editBtn.addEventListener('click', () => {
            display.style.display = 'none';
            edit.style.display = 'block';
            editBtn.style.display = 'none';
            saveBtn.style.display = 'inline-block';
            cancelBtn.style.display = 'inline-block';
            edit.focus();
        });
        cancelBtn.addEventListener('click', () => resetEdit());
        saveBtn.addEventListener('click', () => {
            fetch('/mae/api/items/' + Number(id), {
                method: 'PATCH',
                headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken},
                body: JSON.stringify({caption: edit.value})
            }).then(r => r.json()).then(j => {
                display.textContent = j.caption || 'No caption yet — click edit to add one.';
                resetEdit();
            }).catch(() => alert('Save failed'));
        });

        function resetEdit() {
            display.style.display = 'block';
            edit.style.display = 'none';
            editBtn.style.display = 'inline-block';
            saveBtn.style.display = 'none';
            cancelBtn.style.display = 'none';
        }

        if (deleteBtn) deleteBtn.addEventListener('click', () => {
            if (!confirm('Delete this item permanently?')) return;
            fetch('/mae/api/items/' + Number(id), {
                method: 'DELETE',
                headers: {'X-XSRF-TOKEN': csrfToken}
            }).then(r => {
                if (r.status === 204) window.location.href = '/mae';
                else alert('Delete failed: ' + r.status);
            });
        });

        document.addEventListener('keydown', e => {
            const prev = lightbox.querySelector('.nav-prev');
            const next = lightbox.querySelector('.nav-next');
            if (e.key === 'ArrowLeft' && prev) window.location.href = prev.getAttribute('href');
            if (e.key === 'ArrowRight' && next) window.location.href = next.getAttribute('href');
            if (e.key === 'Escape') window.history.back();
        });
    }

    function getCookie(name) {
        const m = document.cookie.match('(^|;)\\s*' + name + '\\s*=\\s*([^;]+)');
        return m ? decodeURIComponent(m[2]) : '';
    }
})();
```

- [ ] **Step 2: Manual smoke test (full flow)**

Run: `./mvnw spring-boot:run` (with `MAE_PASSWORD=family`).
- Visit `http://localhost:8080/mae/login`, enter `family`.
- Visit `/mae/upload`, drag a JPEG in. Confirm progress bar fills, "✓ added" link appears.
- Click into the lightbox, edit the caption, save, refresh — caption persists.
- Visit `/mae` and confirm the photo appears in "Recently added" and in its EXIF year/month bucket.
- Press ←/→ in the lightbox to confirm keyboard navigation.

Stop the server.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/mae.js
git commit -m "feat(gallery): add mae.js — uploader, lightbox, caption edit, kbd nav"
```

---

## Phase 7 — WebDAV drop folder

### Task 20: GalleryDropSecurityManager + Milton wiring

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/security/GalleryDropSecurityManager.java`
- Modify: `src/main/java/com/davidneto/homepage/webdav/MiltonConfig.java`
- Create: `src/main/java/com/davidneto/homepage/gallery/webdav/GalleryDropConfig.java`

- [ ] **Step 1: Create `GalleryDropSecurityManager`**

```java
package com.davidneto.homepage.gallery.security;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.http.Auth;
import io.milton.http.Request;
import io.milton.http.SecurityManager;
import io.milton.http.http11.auth.DigestResponse;
import io.milton.resource.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

public class GalleryDropSecurityManager implements SecurityManager {

    private final GalleryProperties props;
    private final LoginRateLimiter limiter;
    private final PasswordEncoder encoder;
    private final String passwordHash;

    public GalleryDropSecurityManager(GalleryProperties props, LoginRateLimiter limiter, PasswordEncoder encoder) {
        this.props = props;
        this.limiter = limiter;
        this.encoder = encoder;
        this.passwordHash = (props.getDrop().getPassword() == null || props.getDrop().getPassword().isBlank())
                ? null : encoder.encode(props.getDrop().getPassword());
    }

    @Override
    public Object authenticate(String user, String password) {
        if (passwordHash == null) return null;
        if (!props.getDrop().getUsername().equals(user)) return null;
        if (!encoder.matches(password, passwordHash)) return null;
        return user;
    }

    @Override
    public Object authenticate(DigestResponse digestRequest) { return null; }

    @Override
    public boolean authorise(Request request, Request.Method method, Auth auth, Resource resource) {
        return auth != null && auth.getTag() != null;
    }

    @Override
    public String getRealm(String host) { return "gallery-drop"; }

    @Override
    public boolean isDigestAllowed() { return false; }
}
```

- [ ] **Step 2: Create `GalleryDropConfig` (Spring beans for Milton's filter)**

```java
package com.davidneto.homepage.gallery.webdav;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import com.davidneto.homepage.gallery.security.GalleryDropSecurityManager;
import com.davidneto.homepage.gallery.service.GalleryStorage;
import com.davidneto.homepage.security.LoginRateLimiter;
import io.milton.config.HttpManagerBuilder;
import io.milton.http.HttpManager;
import io.milton.http.fs.FileSystemResourceFactory;
import io.milton.servlet.SpringMiltonFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class GalleryDropConfig {

    /**
     * The bean name "gallery-drop.http.manager" is used by the second SpringMiltonFilter.
     * SpringMiltonFilter looks up the manager bean by name from its init-param
     * "milton.http.manager.bean".
     */
    @Bean(name = "gallery-drop.http.manager")
    public HttpManager galleryDropHttpManager(GalleryStorage storage,
                                              GalleryProperties props,
                                              LoginRateLimiter limiter,
                                              PasswordEncoder encoder) {
        FileSystemResourceFactory rf = new FileSystemResourceFactory(
                storage.dropDir().toFile(),
                new io.milton.http.fs.NullSecurityManager(),
                "gallery-drop");
        GalleryDropSecurityManager sm = new GalleryDropSecurityManager(props, limiter, encoder);
        HttpManagerBuilder b = new HttpManagerBuilder();
        b.setResourceFactory(rf);
        b.setSecurityManager(sm);
        b.setEnableFormAuth(false);
        b.setEnableBasicAuth(true);
        return b.buildHttpManager();
    }

    @Bean
    public FilterRegistrationBean<SpringMiltonFilter> galleryDropFilter() {
        FilterRegistrationBean<SpringMiltonFilter> reg =
                new FilterRegistrationBean<>(new SpringMiltonFilter());
        reg.addUrlPatterns("/gallery-drop/*");
        reg.setName("gallery-drop");
        reg.setOrder(2);
        // Tell this filter instance to look up the named bean (not the default "milton.http.manager"):
        reg.addInitParameter("milton.http.manager.bean", "gallery-drop.http.manager");
        return reg;
    }
}
```

- [ ] **Step 3: Verify both Milton filters coexist (no startup error)**

Run: `./mvnw test`
Expected: PASS (existing WebDAV tests still pass; no bean conflict).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/security/GalleryDropSecurityManager.java \
        src/main/java/com/davidneto/homepage/gallery/webdav/GalleryDropConfig.java
git commit -m "feat(gallery): add /gallery-drop WebDAV endpoint with dedicated SecurityManager"
```

---

### Task 21: WebDavDropFolderScanner

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/WebDavDropFolderScanner.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/WebDavDropFolderScannerIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WebDavDropFolderScannerIT {

    @Autowired GalleryStorage storage;
    @Autowired GalleryItemRepository repo;
    @Autowired WebDavDropFolderScanner scanner;

    @BeforeEach
    void wipe() throws Exception {
        repo.deleteAll();
        // Best-effort cleanup of test-gallery dir
        if (Files.exists(storage.root())) {
            try (var s = Files.walk(storage.root())) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        storage.init();
    }

    private byte[] jpegWithExif(String exifDate) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        return out.toByteArray();
    }

    @Test
    void stableFile_isIngestedAndDeletedFromDrop() throws Exception {
        Path dropFile = storage.dropDir().resolve("photo.jpg");
        Files.write(dropFile, jpegWithExif("2015:08:08 10:00:00"));
        long writeTime = Files.getLastModifiedTime(dropFile).toMillis();

        // Force the file's mtime back so "stable for N seconds" check passes immediately.
        Files.setLastModifiedTime(dropFile, java.nio.file.attribute.FileTime.fromMillis(writeTime - 60_000));

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isFalse();
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void freshlyWrittenFile_isSkippedUntilStable() throws Exception {
        Path dropFile = storage.dropDir().resolve("fresh.jpg");
        Files.write(dropFile, jpegWithExif("2016:01:01 00:00:00"));
        // Leave mtime as "now" — should NOT be picked up.

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isTrue();
        assertThat(repo.count()).isZero();
    }

    @Test
    void unsupportedFile_movedToFailedWithErrorSidecar() throws Exception {
        Path dropFile = storage.dropDir().resolve("bad.txt");
        Files.writeString(dropFile, "not an image");
        Files.setLastModifiedTime(dropFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        scanner.scanOnce();

        assertThat(Files.exists(dropFile)).isFalse();
        assertThat(Files.exists(storage.failedDir().resolve("bad.txt"))).isTrue();
        assertThat(Files.exists(storage.failedDir().resolve("bad.txt.error"))).isTrue();
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL — class missing)**

Run: `./mvnw test -Dtest=WebDavDropFolderScannerIT`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `WebDavDropFolderScanner`**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.config.GalleryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

@Component
public class WebDavDropFolderScanner {

    private static final Logger LOG = LoggerFactory.getLogger(WebDavDropFolderScanner.class);

    private final GalleryStorage storage;
    private final GalleryProperties props;
    private final GalleryIngestService ingest;

    public WebDavDropFolderScanner(GalleryStorage storage, GalleryProperties props, GalleryIngestService ingest) {
        this.storage = storage;
        this.props = props;
        this.ingest = ingest;
    }

    @Scheduled(fixedDelayString = "${app.gallery.drop.scan-interval-seconds:30}000")
    public void scheduledScan() {
        try { scanOnce(); }
        catch (Exception e) { LOG.error("drop scan failed", e); }
    }

    public void scanOnce() throws IOException {
        Path drop = storage.dropDir();
        if (!Files.isDirectory(drop)) return;

        long stableCutoff = System.currentTimeMillis() - (props.getDrop().getStableAfterSeconds() * 1000L);

        try (Stream<Path> walk = Files.walk(drop)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(storage.failedDir()))
                .forEach(p -> handle(p, stableCutoff));
        }
    }

    private void handle(Path file, long stableCutoff) {
        try {
            FileTime mtime = Files.getLastModifiedTime(file);
            if (mtime.toMillis() > stableCutoff) return; // not stable yet

            try (InputStream in = Files.newInputStream(file)) {
                ingest.ingest(in, file.getFileName().toString(), null, null);
            }
            Files.deleteIfExists(file);
        } catch (GalleryIngestService.UnsupportedMediaException e) {
            moveToFailed(file, e.getMessage());
        } catch (Exception e) {
            LOG.warn("ingest failed for {}: {}", file, e.toString());
            moveToFailed(file, e.toString());
        }
    }

    private void moveToFailed(Path file, String reason) {
        try {
            Path target = storage.failedDir().resolve(file.getFileName());
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(storage.failedDir().resolve(file.getFileName().toString() + ".error"),
                    reason == null ? "" : reason);
        } catch (Exception ex) {
            LOG.error("could not move {} to failed dir", file, ex);
        }
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=WebDavDropFolderScannerIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/WebDavDropFolderScanner.java \
        src/test/java/com/davidneto/homepage/gallery/service/WebDavDropFolderScannerIT.java
git commit -m "feat(gallery): add WebDavDropFolderScanner (scheduled, stability check, failed dir)"
```

---

## Phase 8 — Bulk importer

### Task 22: BulkImporter ApplicationRunner

**Files:**
- Create: `src/main/java/com/davidneto/homepage/gallery/service/BulkImporter.java`
- Test: `src/test/java/com/davidneto/homepage/gallery/service/BulkImporterIT.java`

The runner is only registered when `--spring.profiles.active=bulkimport` (or env `SPRING_PROFILES_ACTIVE=bulkimport`) is active. It walks `--gallery.import.path` and ingests every supported file. The test activates the profile programmatically by instantiating the runner directly.

- [ ] **Step 1: Write the failing test**

```java
package com.davidneto.homepage.gallery.service;

import com.davidneto.homepage.gallery.repository.GalleryItemRepository;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BulkImporterIT {

    @Autowired GalleryItemRepository repo;
    @Autowired GalleryStorage storage;
    @Autowired GalleryIngestService ingest;

    @BeforeEach
    void wipe() throws Exception {
        repo.deleteAll();
        if (Files.exists(storage.root())) {
            try (var s = Files.walk(storage.root())) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
        storage.init();
    }

    private void writeJpegWithExif(Path file, String exifDate) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        TiffOutputSet os = new TiffOutputSet();
        os.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, exifDate);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(baos.toByteArray(), out, os);
        Files.createDirectories(file.getParent());
        Files.write(file, out.toByteArray());
    }

    @Test
    void importDirectory_walksTreeAndIngestsAllSupportedFiles() throws Exception {
        Path src = Files.createTempDirectory("bulk-src-");
        writeJpegWithExif(src.resolve("a.jpg"), "2010:01:01 00:00:00");
        writeJpegWithExif(src.resolve("sub/b.jpg"), "2011:02:02 00:00:00");
        // unsupported file:
        Files.writeString(src.resolve("readme.txt"), "ignore me");

        BulkImporter importer = new BulkImporter(ingest);
        importer.importDirectory(src, "David");

        assertThat(repo.count()).isEqualTo(2);
        assertThat(repo.findAll().stream().map(g -> g.getUploaderName())).containsOnly("David");
    }

    @Test
    void importDirectory_isIdempotent_dedupedOnSecondRun() throws Exception {
        Path src = Files.createTempDirectory("bulk-src-");
        writeJpegWithExif(src.resolve("a.jpg"), "2012:03:03 00:00:00");

        BulkImporter importer = new BulkImporter(ingest);
        importer.importDirectory(src, null);
        importer.importDirectory(src, null);

        assertThat(repo.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test (expect FAIL)**

Run: `./mvnw test -Dtest=BulkImporterIT`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `BulkImporter`**

```java
package com.davidneto.homepage.gallery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@Component
@Profile("bulkimport")
public class BulkImporter implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BulkImporter.class);

    private final GalleryIngestService ingest;

    @Value("${gallery.import.path:}")
    private String importPath;

    @Value("${gallery.import.uploader:}")
    private String uploader;

    public BulkImporter(GalleryIngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (importPath == null || importPath.isBlank()) {
            LOG.error("bulkimport profile active but --gallery.import.path is not set");
            return;
        }
        importDirectory(Path.of(importPath), uploader == null || uploader.isBlank() ? null : uploader);
    }

    public void importDirectory(Path root, String uploaderName) throws Exception {
        AtomicLong scanned = new AtomicLong();
        AtomicLong ingested = new AtomicLong();
        AtomicLong deduped = new AtomicLong();
        AtomicLong rejected = new AtomicLong();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                long n = scanned.incrementAndGet();
                try (InputStream in = Files.newInputStream(p)) {
                    var r = ingest.ingest(in, p.getFileName().toString(), null, uploaderName);
                    if (r.deduped()) deduped.incrementAndGet();
                    else ingested.incrementAndGet();
                } catch (GalleryIngestService.UnsupportedMediaException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    LOG.warn("failed to ingest {}: {}", p, e.toString());
                    rejected.incrementAndGet();
                }
                if (n % 50 == 0) {
                    LOG.info("scanned={}, ingested={}, deduped={}, rejected={}",
                            n, ingested.get(), deduped.get(), rejected.get());
                }
            });
        }

        LOG.info("bulk import complete: scanned={}, ingested={}, deduped={}, rejected={}",
                scanned.get(), ingested.get(), deduped.get(), rejected.get());
    }
}
```

- [ ] **Step 4: Run the test (expect PASS)**

Run: `./mvnw test -Dtest=BulkImporterIT`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/gallery/service/BulkImporter.java \
        src/test/java/com/davidneto/homepage/gallery/service/BulkImporterIT.java
git commit -m "feat(gallery): add BulkImporter ApplicationRunner under bulkimport profile"
```

---

## Phase 9 — Operational config

### Task 23: docker-compose, Caddyfile, Dockerfile (ffmpeg)

**Files:**
- Modify: `Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `Caddyfile`

- [ ] **Step 1: Update `Dockerfile` to install ffmpeg in the runtime stage**

Read current `Dockerfile`, then add `ffmpeg` to the runtime apk install line. If the Dockerfile uses a multi-stage build with `eclipse-temurin:21-jre-alpine` (or similar), add to the runtime stage:

```dockerfile
RUN apk add --no-cache ffmpeg
```

Place this in the runtime stage, before any `USER` directive. (If the base image is Debian-based, use `apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*` instead.)

Verify with `docker build .` (locally; if not building locally, defer to CI).

- [ ] **Step 2: Update `docker-compose.yml`**

Add the new env vars to the `app` service environment block, the new volume mount, and the new named volume:

```yaml
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - app

  app:
    build: .
    restart: unless-stopped
    environment:
      - DB_HOST=postgres
      - DB_PORT=5432
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
      - ADMIN_USERNAME=${ADMIN_USERNAME}
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
      - UPLOAD_DIR=/app/uploads
      - WEBDAV_ROOT_DIR=/app/webdav
      - GALLERY_ROOT_DIR=/app/gallery
      - MAE_PASSWORD=${MAE_PASSWORD}
      - MAE_TITLE=${MAE_TITLE}
      - WEBDAV_DROP_USERNAME=${WEBDAV_DROP_USERNAME}
      - WEBDAV_DROP_PASSWORD=${WEBDAV_DROP_PASSWORD}
    volumes:
      - uploads:/app/uploads
      - webdav_data:/app/webdav
      - gallery_data:/app/gallery
    depends_on:
      postgres:
        condition: service_healthy

  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  pgdata:
  uploads:
  caddy_data:
  caddy_config:
  webdav_data:
  gallery_data:
```

- [ ] **Step 3: Update `Caddyfile` to add the drop hostname**

Final content:

```
davidneto.eu, www.davidneto.eu {
    reverse_proxy app:8080 {
        header_up X-Forwarded-For {http.request.remote.host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
    }
}

cloud.davidneto.eu {
    rewrite * /webdav{uri}
    reverse_proxy app:8080 {
        header_up X-Forwarded-For {http.request.remote.host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
    }
}

drop.davidneto.eu {
    rewrite * /gallery-drop{uri}
    reverse_proxy app:8080 {
        header_up X-Forwarded-For {http.request.remote.host}
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host {host}
    }
}
```

- [ ] **Step 4: Verify the full test suite still passes**

Run: `./mvnw test`
Expected: PASS (all suites green).

- [ ] **Step 5: Commit**

```bash
git add Dockerfile docker-compose.yml Caddyfile
git commit -m "ops(gallery): add ffmpeg, gallery_data volume, drop.davidneto.eu hostname"
```

---

## Phase 10 — One-time bulk import (operational, not code)

### Task 24: Run the bulk importer for the existing ~2200 photos

This is a runbook step, not a code task. Execute once after the app is deployed.

- [ ] **Step 1: Stage the photos on the server**

`rsync -avz /local/path/to/mae-photos/ user@server:/srv/mae-import/`

- [ ] **Step 2: Run the importer in a one-shot container**

```bash
docker compose run --rm \
  -v /srv/mae-import:/import:ro \
  -e SPRING_PROFILES_ACTIVE=bulkimport \
  app java -jar /app/app.jar \
  --spring.profiles.active=bulkimport \
  --gallery.import.path=/import \
  --gallery.import.uploader=David
```

Expected: progress lines every 50 files, final summary like `bulk import complete: scanned=2210, ingested=2208, deduped=0, rejected=2`.

- [ ] **Step 3: Spot-check the gallery**

Visit `https://davidneto.eu/mae`, log in with the family password, browse a few year/month buckets. Expect items grouped by EXIF DateTimeOriginal.

- [ ] **Step 4: Remove the staging directory**

`ssh user@server 'rm -rf /srv/mae-import'`

- [ ] **Step 5: Investigate any rejected files**

Look at the importer log output for rejected file names (each is logged at WARN). Decide per file whether to fix metadata and retry, or leave them out.

---

## Plan summary

24 tasks across 10 phases. Approximate task count by phase:

| Phase | Tasks | What ships |
|---|---|---|
| 1. Foundation | 1–3 | Deps, config, V4 schema, entity, repo |
| 2. Storage + extraction | 4–8 | Storage, EXIF, ffprobe, thumbs, posters |
| 3. Ingest | 9 | The single ingest pipeline |
| 4. Security | 10–11 | Filter chain ordering + dual-password login |
| 5. API + media + robots | 12–14 | JSON endpoints + media serving + noindex |
| 6. HTML pages, CSS, JS | 15–19 | Controller, mae.css, templates, mae.js |
| 7. WebDAV drop | 20–21 | Second Milton filter + scheduled scanner |
| 8. Bulk importer | 22 | One-time `ApplicationRunner` |
| 9. Operational | 23 | Dockerfile (ffmpeg), compose volume, Caddy |
| 10. Runbook | 24 | Run the importer for ~2200 photos |

**Parallelization graph for the dispatcher:**

- 1 → 2 → 3
- After 3:
  - Tasks 4–8 (Phase 2) — fully parallel.
- After Phase 2:
  - Task 9 (Phase 3) — sequential.
- After 9:
  - Phase 4 (10–11), Phase 5 (12–14), Phase 7 (20–21), Phase 8 (22) — parallelizable.
- After Phase 5:
  - Phase 6 (15–19) — internally mostly sequential (15 → 16 → 17 → 18 → 19).
- Phase 9 (23) and Phase 10 (24) — sequential, run last.

