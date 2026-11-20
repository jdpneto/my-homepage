# Personal Homepage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a personal homepage with blog, static page CMS, and admin panel — deployed via Docker Compose on Hetzner VPS.

**Architecture:** Spring Boot 3 monolith with Thymeleaf server-side rendering. PostgreSQL for storage, Flyway for migrations, Spring Security for admin auth. Caddy reverse proxy in Docker Compose. Terminal-themed black/green CSS.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data JPA, Spring Security, Thymeleaf, PostgreSQL 16, Flyway, commonmark-java, EasyMDE, Caddy 2, Docker Compose.

---

## File Structure

```
my-homepage/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── Caddyfile
├── .env.example
├── src/main/java/com/davidneto/homepage/
│   ├── HomepageApplication.java
│   ├── config/
│   │   └── SecurityConfig.java
│   ├── entity/
│   │   ├── BlogPost.java
│   │   ├── StaticPage.java
│   │   ├── SocialLink.java
│   │   └── SiteConfig.java
│   ├── repository/
│   │   ├── BlogPostRepository.java
│   │   ├── StaticPageRepository.java
│   │   ├── SocialLinkRepository.java
│   │   └── SiteConfigRepository.java
│   ├── service/
│   │   ├── BlogPostService.java
│   │   ├── StaticPageService.java
│   │   ├── SocialLinkService.java
│   │   ├── SiteConfigService.java
│   │   └── MarkdownService.java
│   └── controller/
│       ├── HomeController.java
│       ├── BlogController.java
│       ├── PageController.java
│       └── AdminController.java
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/
│   │   └── V1__initial_schema.sql
│   ├── static/
│   │   ├── css/
│   │   │   └── terminal.css
│   │   └── js/
│   │       └── admin.js
│   └── templates/
│       ├── layout.html
│       ├── home.html
│       ├── blog/
│       │   ├── list.html
│       │   └── post.html
│       ├── pages/
│       │   └── view.html
│       └── admin/
│           ├── login.html
│           ├── posts.html
│           ├── post-editor.html
│           ├── pages.html
│           ├── page-editor.html
│           ├── social-links.html
│           └── settings.html
└── src/test/java/com/davidneto/homepage/
    ├── HomepageApplicationTests.java
    ├── service/
    │   ├── BlogPostServiceTest.java
    │   ├── StaticPageServiceTest.java
    │   ├── SocialLinkServiceTest.java
    │   ├── SiteConfigServiceTest.java
    │   └── MarkdownServiceTest.java
    ├── controller/
    │   ├── HomeControllerTest.java
    │   ├── BlogControllerTest.java
    │   ├── PageControllerTest.java
    │   └── AdminControllerTest.java
    └── repository/
        ├── BlogPostRepositoryTest.java
        └── StaticPageRepositoryTest.java
```

---

### Task 1: Spring Boot Project Scaffolding

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/davidneto/homepage/HomepageApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/davidneto/homepage/HomepageApplicationTests.java`

- [ ] **Step 1: Create pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
        <relativePath/>
    </parent>
    <groupId>com.davidneto</groupId>
    <artifactId>homepage</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>homepage</name>
    <description>Personal homepage and blog</description>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.thymeleaf.extras</groupId>
            <artifactId>thymeleaf-extras-springsecurity6</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.commonmark</groupId>
            <artifactId>commonmark</artifactId>
            <version>0.24.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create application main class**

Create `src/main/java/com/davidneto/homepage/HomepageApplication.java`:

```java
package com.davidneto.homepage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HomepageApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomepageApplication.class, args);
    }
}
```

- [ ] **Step 3: Create application.yml**

Create `src/main/resources/application.yml`:

```yaml
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

app:
  admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin}
  upload-dir: ${UPLOAD_DIR:./uploads}
```

- [ ] **Step 4: Create test application.yml for H2**

Create `src/test/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false

app:
  admin:
    username: admin
    password: testpass
  upload-dir: /tmp/homepage-test-uploads
```

- [ ] **Step 5: Create context load test**

Create `src/test/java/com/davidneto/homepage/HomepageApplicationTests.java`:

```java
package com.davidneto.homepage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HomepageApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: Update .gitignore for Maven/Spring Boot**

Append to `.gitignore`:

```
# Maven
target/

# IDE
.idea/
*.iml
.vscode/
.settings/
.project
.classpath

# Spring Boot
*.jar
*.war

# Environment
.env

# Uploads
uploads/

# Superpowers
.superpowers/
```

- [ ] **Step 7: Verify project compiles and test passes**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, 1 test passed

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/ .gitignore .mvn mvnw mvnw.cmd
git commit -m "feat: scaffold Spring Boot project with dependencies"
```

---

### Task 2: Database Schema and Flyway Migration

**Files:**
- Create: `src/main/resources/db/migration/V1__initial_schema.sql`

- [ ] **Step 1: Create initial migration**

Create `src/main/resources/db/migration/V1__initial_schema.sql`:

```sql
CREATE TABLE blog_post (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    content TEXT,
    excerpt VARCHAR(500),
    tags VARCHAR(500),
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP
);

CREATE TABLE static_page (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    content TEXT,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE social_link (
    id BIGSERIAL PRIMARY KEY,
    platform VARCHAR(50) NOT NULL,
    url VARCHAR(500) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    icon VARCHAR(50),
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE site_config (
    id BIGSERIAL PRIMARY KEY,
    key VARCHAR(100) NOT NULL UNIQUE,
    value TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO site_config (key, value) VALUES ('site.name', 'David Neto');
INSERT INTO site_config (key, value) VALUES ('site.tagline', 'Software Developer');
INSERT INTO site_config (key, value) VALUES ('site.photo_path', '');
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/
git commit -m "feat: add Flyway initial schema migration"
```

---

### Task 3: JPA Entities

**Files:**
- Create: `src/main/java/com/davidneto/homepage/entity/BlogPost.java`
- Create: `src/main/java/com/davidneto/homepage/entity/StaticPage.java`
- Create: `src/main/java/com/davidneto/homepage/entity/SocialLink.java`
- Create: `src/main/java/com/davidneto/homepage/entity/SiteConfig.java`

- [ ] **Step 1: Create BlogPost entity**

Create `src/main/java/com/davidneto/homepage/entity/BlogPost.java`:

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "blog_post")
public class BlogPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String excerpt;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
```

- [ ] **Step 2: Create StaticPage entity**

Create `src/main/java/com/davidneto/homepage/entity/StaticPage.java`:

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "static_page")
public class StaticPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 3: Create SocialLink entity**

Create `src/main/java/com/davidneto/homepage/entity/SocialLink.java`:

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "social_link")
public class SocialLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String platform;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(length = 50)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
```

- [ ] **Step 4: Create SiteConfig entity**

Create `src/main/java/com/davidneto/homepage/entity/SiteConfig.java`:

```java
package com.davidneto.homepage.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "site_config")
public class SiteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/davidneto/homepage/entity/
git commit -m "feat: add JPA entities for BlogPost, StaticPage, SocialLink, SiteConfig"
```

---

### Task 4: Repositories

**Files:**
- Create: `src/main/java/com/davidneto/homepage/repository/BlogPostRepository.java`
- Create: `src/main/java/com/davidneto/homepage/repository/StaticPageRepository.java`
- Create: `src/main/java/com/davidneto/homepage/repository/SocialLinkRepository.java`
- Create: `src/main/java/com/davidneto/homepage/repository/SiteConfigRepository.java`
- Create: `src/test/java/com/davidneto/homepage/repository/BlogPostRepositoryTest.java`
- Create: `src/test/java/com/davidneto/homepage/repository/StaticPageRepositoryTest.java`

- [ ] **Step 1: Create BlogPostRepository**

Create `src/main/java/com/davidneto/homepage/repository/BlogPostRepository.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.BlogPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BlogPostRepository extends JpaRepository<BlogPost, Long> {

    Page<BlogPost> findByPublishedTrueOrderByPublishedAtDesc(Pageable pageable);

    Optional<BlogPost> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
```

- [ ] **Step 2: Create StaticPageRepository**

Create `src/main/java/com/davidneto/homepage/repository/StaticPageRepository.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.StaticPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaticPageRepository extends JpaRepository<StaticPage, Long> {

    Optional<StaticPage> findBySlugAndPublishedTrue(String slug);

    Optional<StaticPage> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
```

- [ ] **Step 3: Create SocialLinkRepository**

Create `src/main/java/com/davidneto/homepage/repository/SocialLinkRepository.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.SocialLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocialLinkRepository extends JpaRepository<SocialLink, Long> {

    List<SocialLink> findAllByOrderBySortOrderAsc();
}
```

- [ ] **Step 4: Create SiteConfigRepository**

Create `src/main/java/com/davidneto/homepage/repository/SiteConfigRepository.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.SiteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteConfigRepository extends JpaRepository<SiteConfig, Long> {

    Optional<SiteConfig> findByKey(String key);
}
```

- [ ] **Step 5: Write BlogPostRepository test**

Create `src/test/java/com/davidneto/homepage/repository/BlogPostRepositoryTest.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.BlogPost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BlogPostRepositoryTest {

    @Autowired
    private BlogPostRepository blogPostRepository;

    @Test
    void findByPublishedTrue_returnsOnlyPublishedPosts() {
        BlogPost published = new BlogPost();
        published.setTitle("Published Post");
        published.setSlug("published-post");
        published.setPublished(true);
        published.setPublishedAt(LocalDateTime.now());
        blogPostRepository.save(published);

        BlogPost draft = new BlogPost();
        draft.setTitle("Draft Post");
        draft.setSlug("draft-post");
        draft.setPublished(false);
        blogPostRepository.save(draft);

        Page<BlogPost> result = blogPostRepository
                .findByPublishedTrueOrderByPublishedAtDesc(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSlug()).isEqualTo("published-post");
    }

    @Test
    void findBySlug_returnsPost() {
        BlogPost post = new BlogPost();
        post.setTitle("Test Post");
        post.setSlug("test-post");
        blogPostRepository.save(post);

        assertThat(blogPostRepository.findBySlug("test-post")).isPresent();
        assertThat(blogPostRepository.findBySlug("nonexistent")).isEmpty();
    }
}
```

- [ ] **Step 6: Write StaticPageRepository test**

Create `src/test/java/com/davidneto/homepage/repository/StaticPageRepositoryTest.java`:

```java
package com.davidneto.homepage.repository;

import com.davidneto.homepage.entity.StaticPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class StaticPageRepositoryTest {

    @Autowired
    private StaticPageRepository staticPageRepository;

    @Test
    void findBySlugAndPublishedTrue_returnsOnlyPublishedPages() {
        StaticPage published = new StaticPage();
        published.setTitle("Privacy Policy");
        published.setSlug("privacy-policy");
        published.setPublished(true);
        staticPageRepository.save(published);

        StaticPage draft = new StaticPage();
        draft.setTitle("Draft Page");
        draft.setSlug("draft-page");
        draft.setPublished(false);
        staticPageRepository.save(draft);

        assertThat(staticPageRepository.findBySlugAndPublishedTrue("privacy-policy")).isPresent();
        assertThat(staticPageRepository.findBySlugAndPublishedTrue("draft-page")).isEmpty();
    }

    @Test
    void existsBySlug_checksExistence() {
        StaticPage page = new StaticPage();
        page.setTitle("Test Page");
        page.setSlug("test-page");
        staticPageRepository.save(page);

        assertThat(staticPageRepository.existsBySlug("test-page")).isTrue();
        assertThat(staticPageRepository.existsBySlug("nope")).isFalse();
    }
}
```

- [ ] **Step 7: Run tests**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/davidneto/homepage/repository/ src/test/java/com/davidneto/homepage/repository/
git commit -m "feat: add Spring Data repositories with query methods and tests"
```

---

### Task 5: Markdown Service

**Files:**
- Create: `src/main/java/com/davidneto/homepage/service/MarkdownService.java`
- Create: `src/test/java/com/davidneto/homepage/service/MarkdownServiceTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/davidneto/homepage/service/MarkdownServiceTest.java`:

```java
package com.davidneto.homepage.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService markdownService = new MarkdownService();

    @Test
    void renderHeading() {
        String html = markdownService.render("# Hello");
        assertThat(html).contains("<h1>Hello</h1>");
    }

    @Test
    void renderParagraph() {
        String html = markdownService.render("Some text");
        assertThat(html).contains("<p>Some text</p>");
    }

    @Test
    void renderCodeBlock() {
        String html = markdownService.render("```java\nint x = 1;\n```");
        assertThat(html).contains("<code");
        assertThat(html).contains("int x = 1;");
    }

    @Test
    void renderNullReturnsEmpty() {
        String html = markdownService.render(null);
        assertThat(html).isEmpty();
    }

    @Test
    void renderEmptyReturnsEmpty() {
        String html = markdownService.render("");
        assertThat(html).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=MarkdownServiceTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement MarkdownService**

Create `src/main/java/com/davidneto/homepage/service/MarkdownService.java`:

```java
package com.davidneto.homepage.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

@Service
public class MarkdownService {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=MarkdownServiceTest`
Expected: BUILD SUCCESS, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/MarkdownService.java src/test/java/com/davidneto/homepage/service/MarkdownServiceTest.java
git commit -m "feat: add Markdown rendering service using commonmark-java"
```

---

### Task 6: Blog Post Service

**Files:**
- Create: `src/main/java/com/davidneto/homepage/service/BlogPostService.java`
- Create: `src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.repository.BlogPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogPostServiceTest {

    @Mock
    private BlogPostRepository blogPostRepository;

    @InjectMocks
    private BlogPostService blogPostService;

    private BlogPost post;

    @BeforeEach
    void setUp() {
        post = new BlogPost();
        post.setId(1L);
        post.setTitle("Test Post");
        post.setSlug("test-post");
        post.setContent("# Hello");
        post.setPublished(false);
    }

    @Test
    void getPublishedPosts_returnsPage() {
        Page<BlogPost> page = new PageImpl<>(List.of(post));
        when(blogPostRepository.findByPublishedTrueOrderByPublishedAtDesc(any()))
                .thenReturn(page);

        Page<BlogPost> result = blogPostService.getPublishedPosts(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getBySlug_returnsPost() {
        when(blogPostRepository.findBySlug("test-post")).thenReturn(Optional.of(post));

        assertThat(blogPostService.getBySlug("test-post")).isPresent();
    }

    @Test
    void save_generatesSlugFromTitle() {
        BlogPost newPost = new BlogPost();
        newPost.setTitle("My New Blog Post!");
        when(blogPostRepository.existsBySlug("my-new-blog-post")).thenReturn(false);
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost saved = blogPostService.save(newPost);

        assertThat(saved.getSlug()).isEqualTo("my-new-blog-post");
    }

    @Test
    void save_deduplicatesSlug() {
        BlogPost newPost = new BlogPost();
        newPost.setTitle("Test Post");
        when(blogPostRepository.existsBySlug("test-post")).thenReturn(true);
        when(blogPostRepository.existsBySlug("test-post-2")).thenReturn(false);
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost saved = blogPostService.save(newPost);

        assertThat(saved.getSlug()).isEqualTo("test-post-2");
    }

    @Test
    void publish_setsPublishedAtOnFirstPublish() {
        when(blogPostRepository.findById(1L)).thenReturn(Optional.of(post));
        when(blogPostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BlogPost published = blogPostService.publish(1L);

        assertThat(published.isPublished()).isTrue();
        assertThat(published.getPublishedAt()).isNotNull();
    }

    @Test
    void delete_removesPost() {
        when(blogPostRepository.findById(1L)).thenReturn(Optional.of(post));

        blogPostService.delete(1L);

        verify(blogPostRepository).delete(post);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=BlogPostServiceTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement BlogPostService**

Create `src/main/java/com/davidneto/homepage/service/BlogPostService.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.repository.BlogPostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BlogPostService {

    private final BlogPostRepository blogPostRepository;

    public BlogPostService(BlogPostRepository blogPostRepository) {
        this.blogPostRepository = blogPostRepository;
    }

    public Page<BlogPost> getPublishedPosts(Pageable pageable) {
        return blogPostRepository.findByPublishedTrueOrderByPublishedAtDesc(pageable);
    }

    public Optional<BlogPost> getBySlug(String slug) {
        return blogPostRepository.findBySlug(slug);
    }

    public Optional<BlogPost> getById(Long id) {
        return blogPostRepository.findById(id);
    }

    public List<BlogPost> getAll() {
        return blogPostRepository.findAll();
    }

    public BlogPost save(BlogPost post) {
        if (post.getSlug() == null || post.getSlug().isBlank()) {
            post.setSlug(generateSlug(post.getTitle()));
        }
        return blogPostRepository.save(post);
    }

    public BlogPost publish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(true);
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        return blogPostRepository.save(post);
    }

    public BlogPost unpublish(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        post.setPublished(false);
        return blogPostRepository.save(post);
    }

    public void delete(Long id) {
        BlogPost post = blogPostRepository.findById(id).orElseThrow();
        blogPostRepository.delete(post);
    }

    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String slug = base;
        int counter = 2;
        while (blogPostRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=BlogPostServiceTest`
Expected: BUILD SUCCESS, 6 tests passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/BlogPostService.java src/test/java/com/davidneto/homepage/service/BlogPostServiceTest.java
git commit -m "feat: add BlogPostService with CRUD, slug generation, publish/unpublish"
```

---

### Task 7: Static Page Service

**Files:**
- Create: `src/main/java/com/davidneto/homepage/service/StaticPageService.java`
- Create: `src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java`

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.repository.StaticPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaticPageServiceTest {

    @Mock
    private StaticPageRepository staticPageRepository;

    @InjectMocks
    private StaticPageService staticPageService;

    private StaticPage page;

    @BeforeEach
    void setUp() {
        page = new StaticPage();
        page.setId(1L);
        page.setTitle("Privacy Policy");
        page.setSlug("privacy-policy");
        page.setContent("# Privacy Policy\nWe respect your privacy.");
    }

    @Test
    void getPublishedBySlug_returnsPublishedPage() {
        when(staticPageRepository.findBySlugAndPublishedTrue("privacy-policy"))
                .thenReturn(Optional.of(page));

        assertThat(staticPageService.getPublishedBySlug("privacy-policy")).isPresent();
    }

    @Test
    void save_generatesSlugFromTitle() {
        StaticPage newPage = new StaticPage();
        newPage.setTitle("Terms of Service!");
        when(staticPageRepository.existsBySlug("terms-of-service")).thenReturn(false);
        when(staticPageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StaticPage saved = staticPageService.save(newPage);

        assertThat(saved.getSlug()).isEqualTo("terms-of-service");
    }

    @Test
    void delete_removesPage() {
        when(staticPageRepository.findById(1L)).thenReturn(Optional.of(page));

        staticPageService.delete(1L);

        verify(staticPageRepository).delete(page);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=StaticPageServiceTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement StaticPageService**

Create `src/main/java/com/davidneto/homepage/service/StaticPageService.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.repository.StaticPageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class StaticPageService {

    private final StaticPageRepository staticPageRepository;

    public StaticPageService(StaticPageRepository staticPageRepository) {
        this.staticPageRepository = staticPageRepository;
    }

    public Optional<StaticPage> getPublishedBySlug(String slug) {
        return staticPageRepository.findBySlugAndPublishedTrue(slug);
    }

    public Optional<StaticPage> getById(Long id) {
        return staticPageRepository.findById(id);
    }

    public List<StaticPage> getAll() {
        return staticPageRepository.findAll();
    }

    public StaticPage save(StaticPage page) {
        if (page.getSlug() == null || page.getSlug().isBlank()) {
            page.setSlug(generateSlug(page.getTitle()));
        }
        return staticPageRepository.save(page);
    }

    public void delete(Long id) {
        StaticPage page = staticPageRepository.findById(id).orElseThrow();
        staticPageRepository.delete(page);
    }

    private String generateSlug(String title) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String slug = base;
        int counter = 2;
        while (staticPageRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=StaticPageServiceTest`
Expected: BUILD SUCCESS, 3 tests passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/StaticPageService.java src/test/java/com/davidneto/homepage/service/StaticPageServiceTest.java
git commit -m "feat: add StaticPageService with CRUD and slug generation"
```

---

### Task 8: SocialLink and SiteConfig Services

**Files:**
- Create: `src/main/java/com/davidneto/homepage/service/SocialLinkService.java`
- Create: `src/main/java/com/davidneto/homepage/service/SiteConfigService.java`
- Create: `src/test/java/com/davidneto/homepage/service/SocialLinkServiceTest.java`
- Create: `src/test/java/com/davidneto/homepage/service/SiteConfigServiceTest.java`

- [ ] **Step 1: Write SocialLinkService test**

Create `src/test/java/com/davidneto/homepage/service/SocialLinkServiceTest.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.repository.SocialLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialLinkServiceTest {

    @Mock
    private SocialLinkRepository socialLinkRepository;

    @InjectMocks
    private SocialLinkService socialLinkService;

    @Test
    void getAllSorted_returnsSortedLinks() {
        SocialLink link = new SocialLink();
        link.setPlatform("linkedin");
        link.setUrl("https://linkedin.com/in/test");
        link.setDisplayName("LinkedIn");
        link.setSortOrder(1);

        when(socialLinkRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(link));

        List<SocialLink> result = socialLinkService.getAllSorted();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlatform()).isEqualTo("linkedin");
    }
}
```

- [ ] **Step 2: Write SiteConfigService test**

Create `src/test/java/com/davidneto/homepage/service/SiteConfigServiceTest.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SiteConfig;
import com.davidneto.homepage.repository.SiteConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteConfigServiceTest {

    @Mock
    private SiteConfigRepository siteConfigRepository;

    @InjectMocks
    private SiteConfigService siteConfigService;

    @Test
    void get_returnsValue() {
        SiteConfig config = new SiteConfig();
        config.setKey("site.name");
        config.setValue("David Neto");

        when(siteConfigRepository.findByKey("site.name")).thenReturn(Optional.of(config));

        assertThat(siteConfigService.get("site.name")).isEqualTo("David Neto");
    }

    @Test
    void get_returnsEmptyStringWhenMissing() {
        when(siteConfigRepository.findByKey("missing")).thenReturn(Optional.empty());

        assertThat(siteConfigService.get("missing")).isEmpty();
    }

    @Test
    void set_updatesExistingConfig() {
        SiteConfig config = new SiteConfig();
        config.setKey("site.name");
        config.setValue("Old Name");

        when(siteConfigRepository.findByKey("site.name")).thenReturn(Optional.of(config));
        when(siteConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        siteConfigService.set("site.name", "New Name");

        assertThat(config.getValue()).isEqualTo("New Name");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest="SocialLinkServiceTest,SiteConfigServiceTest"`
Expected: FAIL — classes not found

- [ ] **Step 4: Implement SocialLinkService**

Create `src/main/java/com/davidneto/homepage/service/SocialLinkService.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.repository.SocialLinkRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SocialLinkService {

    private final SocialLinkRepository socialLinkRepository;

    public SocialLinkService(SocialLinkRepository socialLinkRepository) {
        this.socialLinkRepository = socialLinkRepository;
    }

    public List<SocialLink> getAllSorted() {
        return socialLinkRepository.findAllByOrderBySortOrderAsc();
    }

    public Optional<SocialLink> getById(Long id) {
        return socialLinkRepository.findById(id);
    }

    public SocialLink save(SocialLink link) {
        return socialLinkRepository.save(link);
    }

    public void delete(Long id) {
        socialLinkRepository.deleteById(id);
    }
}
```

- [ ] **Step 5: Implement SiteConfigService**

Create `src/main/java/com/davidneto/homepage/service/SiteConfigService.java`:

```java
package com.davidneto.homepage.service;

import com.davidneto.homepage.entity.SiteConfig;
import com.davidneto.homepage.repository.SiteConfigRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SiteConfigService {

    private final SiteConfigRepository siteConfigRepository;

    public SiteConfigService(SiteConfigRepository siteConfigRepository) {
        this.siteConfigRepository = siteConfigRepository;
    }

    public String get(String key) {
        return siteConfigRepository.findByKey(key)
                .map(SiteConfig::getValue)
                .orElse("");
    }

    public void set(String key, String value) {
        SiteConfig config = siteConfigRepository.findByKey(key)
                .orElseGet(() -> {
                    SiteConfig c = new SiteConfig();
                    c.setKey(key);
                    return c;
                });
        config.setValue(value);
        siteConfigRepository.save(config);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest="SocialLinkServiceTest,SiteConfigServiceTest"`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/davidneto/homepage/service/SocialLinkService.java src/main/java/com/davidneto/homepage/service/SiteConfigService.java src/test/java/com/davidneto/homepage/service/
git commit -m "feat: add SocialLinkService and SiteConfigService with tests"
```

---

### Task 9: Security Configuration

**Files:**
- Create: `src/main/java/com/davidneto/homepage/config/SecurityConfig.java`

- [ ] **Step 1: Create SecurityConfig**

Create `src/main/java/com/davidneto/homepage/config/SecurityConfig.java`:

```java
package com.davidneto.homepage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form
                .loginPage("/admin/login")
                .defaultSuccessUrl("/admin/posts", true)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        var user = User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/davidneto/homepage/config/SecurityConfig.java
git commit -m "feat: add Spring Security config with form login for admin routes"
```

---

### Task 10: Terminal CSS Theme

**Files:**
- Create: `src/main/resources/static/css/terminal.css`

- [ ] **Step 1: Create terminal.css**

Create `src/main/resources/static/css/terminal.css`:

```css
/* === Reset & Base === */
*, *::before, *::after {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

:root {
    --bg: #0a0a0a;
    --text: #d4d4d4;
    --text-secondary: #888;
    --text-muted: #555;
    --accent: #00ff00;
    --border: #222;
    --border-light: #333;
    --surface: #111;
    --surface-hover: #1a1a1a;
}

body {
    background-color: var(--bg);
    color: var(--text);
    font-family: 'Courier New', Courier, monospace;
    font-size: 15px;
    line-height: 1.6;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
}

a {
    color: var(--accent);
    text-decoration: none;
}

a:hover {
    text-decoration: underline;
}

/* === Navigation === */
.nav {
    border-bottom: 1px solid var(--border-light);
    padding: 16px 32px;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.nav-prompt {
    color: var(--accent);
    font-weight: bold;
}

.nav-links {
    display: flex;
    gap: 24px;
    list-style: none;
}

.nav-links a {
    color: var(--text-secondary);
}

.nav-links a:hover,
.nav-links a.active {
    color: #fff;
}

.nav-hamburger {
    display: none;
    color: var(--text-secondary);
    font-size: 20px;
    cursor: pointer;
    background: none;
    border: none;
}

/* === Hero === */
.hero {
    padding: 48px 32px;
    display: flex;
    align-items: center;
    gap: 32px;
}

.hero-photo {
    width: 120px;
    height: 120px;
    border-radius: 50%;
    border: 2px solid #444;
    object-fit: cover;
    flex-shrink: 0;
}

.hero-name {
    font-size: 24px;
    color: #fff;
    margin-bottom: 8px;
}

.hero-tagline {
    color: var(--text-secondary);
    font-size: 14px;
    margin-bottom: 16px;
}

.hero-tagline::before {
    content: "> ";
}

/* === Social Links === */
.social-links {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
}

.social-link {
    color: var(--accent);
    font-size: 12px;
    border: 1px solid var(--border-light);
    padding: 4px 12px;
    border-radius: 3px;
    transition: background-color 0.2s;
}

.social-link:hover {
    background-color: var(--surface-hover);
    text-decoration: none;
}

/* === Sections === */
.section {
    padding: 0 32px 32px;
}

.section-header {
    color: var(--accent);
    font-size: 14px;
    margin-bottom: 16px;
}

/* === Post List === */
.post-list {
    border: 1px solid var(--border);
    border-radius: 4px;
}

.post-item {
    padding: 12px 16px;
    border-bottom: 1px solid var(--border);
    display: block;
    color: var(--text);
}

.post-item:last-child {
    border-bottom: none;
}

.post-item:hover {
    background-color: var(--surface-hover);
    text-decoration: none;
}

.post-title {
    color: #fff;
    font-size: 15px;
}

.post-meta {
    color: var(--text-muted);
    font-size: 12px;
    margin-top: 4px;
}

.post-excerpt {
    color: var(--text-secondary);
    font-size: 13px;
    margin-top: 6px;
}

/* === Blog Post Content === */
.post-content {
    max-width: 720px;
    padding: 24px 32px;
    line-height: 1.8;
}

.post-content h1,
.post-content h2,
.post-content h3 {
    color: #fff;
    margin: 24px 0 12px;
    border-bottom: 1px solid var(--border);
    padding-bottom: 4px;
}

.post-content h1 { font-size: 22px; }
.post-content h2 { font-size: 18px; }
.post-content h3 { font-size: 16px; }

.post-content p {
    margin-bottom: 16px;
}

.post-content code {
    color: var(--accent);
    background: var(--surface);
    padding: 2px 6px;
    border-radius: 3px;
    font-size: 14px;
}

.post-content pre {
    background: var(--surface);
    border: 1px solid var(--border-light);
    border-radius: 4px;
    padding: 16px;
    overflow-x: auto;
    margin: 16px 0;
}

.post-content pre code {
    background: none;
    padding: 0;
    color: var(--text);
}

.post-content ul, .post-content ol {
    margin: 0 0 16px 24px;
}

.post-content blockquote {
    border-left: 3px solid var(--accent);
    padding-left: 16px;
    color: var(--text-secondary);
    margin: 16px 0;
}

.post-content img {
    max-width: 100%;
    border-radius: 4px;
}

/* === Pagination === */
.pagination {
    color: var(--text-muted);
    font-size: 13px;
    padding: 16px 0;
    text-align: center;
}

.pagination a {
    color: var(--text-secondary);
}

/* === Post Navigation === */
.post-nav {
    border-top: 1px solid var(--border);
    margin-top: 32px;
    padding-top: 16px;
    display: flex;
    justify-content: space-between;
    font-size: 13px;
    color: var(--text-muted);
}

/* === Footer === */
.footer {
    border-top: 1px solid var(--border);
    padding: 16px 32px;
    color: #444;
    font-size: 12px;
    display: flex;
    justify-content: space-between;
    margin-top: auto;
}

/* === Main Content Area === */
.content {
    flex: 1;
}

/* === Responsive === */
@media (max-width: 768px) {
    .nav {
        padding: 12px 16px;
    }

    .nav-links {
        display: none;
        flex-direction: column;
        position: absolute;
        top: 48px;
        left: 0;
        right: 0;
        background: var(--bg);
        border-bottom: 1px solid var(--border-light);
        padding: 16px;
        gap: 12px;
        z-index: 100;
    }

    .nav-links.open {
        display: flex;
    }

    .nav-hamburger {
        display: block;
    }

    .hero {
        flex-direction: column;
        text-align: center;
        padding: 32px 16px;
    }

    .hero-photo {
        width: 90px;
        height: 90px;
    }

    .social-links {
        justify-content: center;
    }

    .section {
        padding: 0 16px 24px;
    }

    .post-content {
        padding: 16px;
    }

    .footer {
        padding: 12px 16px;
        flex-direction: column;
        gap: 4px;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/css/terminal.css
git commit -m "feat: add terminal-themed CSS"
```

---

### Task 11: Thymeleaf Layout and Public Templates

**Files:**
- Create: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/templates/home.html`
- Create: `src/main/resources/templates/blog/list.html`
- Create: `src/main/resources/templates/blog/post.html`
- Create: `src/main/resources/templates/pages/view.html`

- [ ] **Step 1: Create layout template**

Create `src/main/resources/templates/layout.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${pageTitle != null ? pageTitle + ' — ' + siteName : siteName}">Homepage</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
    <nav class="nav">
        <a href="/" class="nav-prompt" th:text="${siteName != null ? siteName.toLowerCase().replace(' ', '@') + ':~$' : 'user@home:~$'}">david@neto:~$</a>
        <button class="nav-hamburger" onclick="document.querySelector('.nav-links').classList.toggle('open')">&#9776;</button>
        <ul class="nav-links">
            <li><a href="/" th:classappend="${currentPage == 'home' ? 'active' : ''}">home</a></li>
            <li><a href="/blog" th:classappend="${currentPage == 'blog' ? 'active' : ''}">blog</a></li>
        </ul>
    </nav>

    <main class="content" th:insert="~{${view} :: content}"></main>

    <footer class="footer">
        <span>MIT License</span>
        <span>Built with Spring Boot</span>
    </footer>
</body>
</html>
```

- [ ] **Step 2: Create home template**

Create `src/main/resources/templates/home.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="content">
    <div class="hero">
        <img th:if="${photoPath != null and !photoPath.isEmpty()}"
             th:src="@{${photoPath}}" alt="Photo" class="hero-photo">
        <div>
            <div class="hero-name" th:text="${siteName}">David Neto</div>
            <div class="hero-tagline" th:text="${siteTagline}">Software Developer</div>
            <div class="social-links">
                <a th:each="link : ${socialLinks}"
                   th:href="${link.url}"
                   th:text="${link.displayName}"
                   target="_blank"
                   rel="noopener noreferrer"
                   class="social-link"></a>
            </div>
        </div>
    </div>

    <div class="section" th:if="${not #lists.isEmpty(recentPosts)}">
        <div class="section-header">~/blog $ ls -lt | head</div>
        <div class="post-list">
            <a th:each="post : ${recentPosts}"
               th:href="@{/blog/{slug}(slug=${post.slug})}"
               class="post-item">
                <div class="post-title" th:text="${post.title}">Post Title</div>
                <div class="post-meta">
                    <span th:text="${#temporals.format(post.publishedAt, 'yyyy-MM-dd')}">2026-03-28</span>
                    <span th:if="${post.tags != null and !post.tags.isEmpty()}"> &mdash; <span th:text="${post.tags}">tags</span></span>
                </div>
            </a>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 3: Create blog list template**

Create `src/main/resources/templates/blog/list.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="content">
    <div class="section" style="padding-top: 24px;">
        <div class="section-header">~/blog $ find . -name "*.md" | sort -r</div>

        <div th:each="post : ${posts.content}" style="margin-bottom: 20px; padding-bottom: 20px; border-bottom: 1px solid #1a1a1a;">
            <a th:href="@{/blog/{slug}(slug=${post.slug})}" style="text-decoration: none;">
                <div class="post-title" th:text="${post.title}">Post Title</div>
            </a>
            <div class="post-meta">
                <span th:text="${#temporals.format(post.publishedAt, 'yyyy-MM-dd')}">2026-03-28</span>
                <span th:if="${post.tags != null and !post.tags.isEmpty()}"> &mdash; <span th:text="${post.tags}">tags</span></span>
            </div>
            <div class="post-excerpt" th:if="${post.excerpt != null}" th:text="${post.excerpt}">Excerpt here...</div>
        </div>

        <div class="pagination" th:if="${posts.totalPages > 1}">
            <a th:if="${posts.number > 0}" th:href="@{/blog(page=${posts.number - 1})}">&#8592; prev</a>
            <span th:text="'page ' + (${posts.number} + 1) + ' of ' + ${posts.totalPages}">page 1 of 3</span>
            <a th:if="${posts.number < posts.totalPages - 1}" th:href="@{/blog(page=${posts.number + 1})}">next &#8594;</a>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 4: Create blog post template**

Create `src/main/resources/templates/blog/post.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="content">
    <div class="post-content">
        <div class="section-header" th:text="'~/blog $ cat ' + ${post.slug} + '.md'">~/blog $ cat post.md</div>

        <h1 th:text="${post.title}" style="color: #fff; font-size: 22px; margin: 16px 0 8px;">Title</h1>
        <div class="post-meta" style="margin-bottom: 24px;">
            <span th:text="${#temporals.format(post.publishedAt, 'yyyy-MM-dd')}">2026-03-28</span>
            <span th:if="${post.tags != null and !post.tags.isEmpty()}"> &mdash; <span th:text="${post.tags}">tags</span></span>
        </div>

        <div th:utext="${renderedContent}">Content here</div>

        <div class="post-nav">
            <span>
                <a th:if="${prevPost != null}" th:href="@{/blog/{slug}(slug=${prevPost.slug})}">&#8592; <span th:text="${prevPost.title}">prev</span></a>
            </span>
            <span>
                <a th:if="${nextPost != null}" th:href="@{/blog/{slug}(slug=${nextPost.slug})}"><span th:text="${nextPost.title}">next</span> &#8594;</a>
            </span>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 5: Create static page view template**

Create `src/main/resources/templates/pages/view.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
<div th:fragment="content">
    <div class="post-content">
        <div class="section-header" th:text="'~/ $ cat pages/' + ${page.slug} + '.md'">~/ $ cat pages/page.md</div>

        <h1 th:text="${page.title}" style="color: #fff; font-size: 22px; margin: 16px 0 24px;">Title</h1>

        <div th:utext="${renderedContent}">Content here</div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/
git commit -m "feat: add Thymeleaf layout and public templates (home, blog, pages)"
```

---

### Task 12: Public Controllers

**Files:**
- Create: `src/main/java/com/davidneto/homepage/controller/HomeController.java`
- Create: `src/main/java/com/davidneto/homepage/controller/BlogController.java`
- Create: `src/main/java/com/davidneto/homepage/controller/PageController.java`
- Create: `src/test/java/com/davidneto/homepage/controller/HomeControllerTest.java`
- Create: `src/test/java/com/davidneto/homepage/controller/BlogControllerTest.java`
- Create: `src/test/java/com/davidneto/homepage/controller/PageControllerTest.java`

- [ ] **Step 1: Write HomeController test**

Create `src/test/java/com/davidneto/homepage/controller/HomeControllerTest.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.SocialLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HomeController.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BlogPostService blogPostService;

    @MockBean
    private SocialLinkService socialLinkService;

    @MockBean
    private SiteConfigService siteConfigService;

    @Test
    void home_returnsHomePage() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(siteConfigService.get("site.tagline")).thenReturn("Developer");
        when(siteConfigService.get("site.photo_path")).thenReturn("");
        when(socialLinkService.getAllSorted()).thenReturn(Collections.emptyList());
        when(blogPostService.getPublishedPosts(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "home"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=HomeControllerTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement HomeController**

Create `src/main/java/com/davidneto/homepage/controller/HomeController.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.SocialLinkService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final BlogPostService blogPostService;
    private final SocialLinkService socialLinkService;
    private final SiteConfigService siteConfigService;

    public HomeController(BlogPostService blogPostService,
                          SocialLinkService socialLinkService,
                          SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.socialLinkService = socialLinkService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("view", "home");
        model.addAttribute("currentPage", "home");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        model.addAttribute("socialLinks", socialLinkService.getAllSorted());
        model.addAttribute("recentPosts", blogPostService.getPublishedPosts(PageRequest.of(0, 5)).getContent());
        return "layout";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest=HomeControllerTest`
Expected: BUILD SUCCESS

- [ ] **Step 5: Write BlogController test**

Create `src/test/java/com/davidneto/homepage/controller/BlogControllerTest.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BlogController.class)
class BlogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BlogPostService blogPostService;

    @MockBean
    private MarkdownService markdownService;

    @MockBean
    private SiteConfigService siteConfigService;

    @Test
    void blogList_returnsListPage() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getPublishedPosts(any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        mockMvc.perform(get("/blog"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "blog/list"));
    }

    @Test
    void blogPost_returnsPostPage() throws Exception {
        BlogPost post = new BlogPost();
        post.setTitle("Test");
        post.setSlug("test");
        post.setContent("# Hello");
        post.setPublished(true);
        post.setPublishedAt(LocalDateTime.now());

        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getBySlug("test")).thenReturn(Optional.of(post));
        when(markdownService.render("# Hello")).thenReturn("<h1>Hello</h1>");

        mockMvc.perform(get("/blog/test"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "blog/post"));
    }

    @Test
    void blogPost_returns404WhenNotFound() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(blogPostService.getBySlug("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/blog/nonexistent"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 6: Implement BlogController**

Create `src/main/java/com/davidneto/homepage/controller/BlogController.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.service.BlogPostService;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class BlogController {

    private final BlogPostService blogPostService;
    private final MarkdownService markdownService;
    private final SiteConfigService siteConfigService;

    public BlogController(BlogPostService blogPostService,
                          MarkdownService markdownService,
                          SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.markdownService = markdownService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/blog")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("view", "blog/list");
        model.addAttribute("currentPage", "blog");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("posts", blogPostService.getPublishedPosts(PageRequest.of(page, 10)));
        return "layout";
    }

    @GetMapping("/blog/{slug}")
    public String post(@PathVariable String slug, Model model) {
        BlogPost post = blogPostService.getBySlug(slug)
                .filter(BlogPost::isPublished)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("view", "blog/post");
        model.addAttribute("currentPage", "blog");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("pageTitle", post.getTitle());
        model.addAttribute("post", post);
        model.addAttribute("renderedContent", markdownService.render(post.getContent()));
        return "layout";
    }
}
```

- [ ] **Step 7: Write PageController test**

Create `src/test/java/com/davidneto/homepage/controller/PageControllerTest.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.StaticPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageController.class)
class PageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StaticPageService staticPageService;

    @MockBean
    private MarkdownService markdownService;

    @MockBean
    private SiteConfigService siteConfigService;

    @Test
    void viewPage_returnsPage() throws Exception {
        StaticPage page = new StaticPage();
        page.setTitle("Privacy Policy");
        page.setSlug("privacy-policy");
        page.setContent("# Privacy");
        page.setPublished(true);

        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(staticPageService.getPublishedBySlug("privacy-policy")).thenReturn(Optional.of(page));
        when(markdownService.render("# Privacy")).thenReturn("<h1>Privacy</h1>");

        mockMvc.perform(get("/pages/privacy-policy"))
                .andExpect(status().isOk())
                .andExpect(view().name("layout"))
                .andExpect(model().attribute("view", "pages/view"));
    }

    @Test
    void viewPage_returns404WhenNotFound() throws Exception {
        when(siteConfigService.get("site.name")).thenReturn("David Neto");
        when(staticPageService.getPublishedBySlug("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/pages/nope"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 8: Implement PageController**

Create `src/main/java/com/davidneto/homepage/controller/PageController.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.MarkdownService;
import com.davidneto.homepage.service.SiteConfigService;
import com.davidneto.homepage.service.StaticPageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class PageController {

    private final StaticPageService staticPageService;
    private final MarkdownService markdownService;
    private final SiteConfigService siteConfigService;

    public PageController(StaticPageService staticPageService,
                          MarkdownService markdownService,
                          SiteConfigService siteConfigService) {
        this.staticPageService = staticPageService;
        this.markdownService = markdownService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/pages/{slug}")
    public String view(@PathVariable String slug, Model model) {
        StaticPage page = staticPageService.getPublishedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("view", "pages/view");
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("pageTitle", page.getTitle());
        model.addAttribute("page", page);
        model.addAttribute("renderedContent", markdownService.render(page.getContent()));
        return "layout";
    }
}
```

- [ ] **Step 9: Run all tests**

Run: `./mvnw test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/davidneto/homepage/controller/ src/test/java/com/davidneto/homepage/controller/
git commit -m "feat: add public controllers for home, blog, and static pages"
```

---

### Task 13: Admin Templates

**Files:**
- Create: `src/main/resources/templates/admin/login.html`
- Create: `src/main/resources/templates/admin/posts.html`
- Create: `src/main/resources/templates/admin/post-editor.html`
- Create: `src/main/resources/templates/admin/pages.html`
- Create: `src/main/resources/templates/admin/page-editor.html`
- Create: `src/main/resources/templates/admin/social-links.html`
- Create: `src/main/resources/templates/admin/settings.html`
- Create: `src/main/resources/static/js/admin.js`

- [ ] **Step 1: Create admin login template**

Create `src/main/resources/templates/admin/login.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin Login</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body style="display: flex; align-items: center; justify-content: center; min-height: 100vh;">
    <div style="width: 100%; max-width: 400px; padding: 32px;">
        <div style="color: var(--accent); margin-bottom: 24px; font-size: 14px;">admin@neto:~$ login</div>
        <div th:if="${param.error}" style="color: #ff4444; margin-bottom: 16px; font-size: 13px;">
            Invalid username or password.
        </div>
        <form th:action="@{/admin/login}" method="post">
            <div style="margin-bottom: 16px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Username</label>
                <input type="text" name="username" required
                       style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 10px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
            </div>
            <div style="margin-bottom: 24px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Password</label>
                <input type="password" name="password" required
                       style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 10px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
            </div>
            <button type="submit"
                    style="width: 100%; background: var(--accent); color: var(--bg); padding: 10px; border: none; font-family: inherit; font-size: 14px; font-weight: bold; border-radius: 3px; cursor: pointer;">
                login
            </button>
        </form>
    </div>
</body>
</html>
```

- [ ] **Step 2: Create admin posts list template**

Create `src/main/resources/templates/admin/posts.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Posts</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: #fff; border-bottom: 1px solid var(--accent);">Posts</a>
            <a href="/admin/pages" style="color: var(--text-secondary);">Pages</a>
            <a href="/admin/social-links" style="color: var(--text-secondary);">Social Links</a>
            <a href="/admin/settings" style="color: var(--text-secondary);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <div class="section-header" style="margin-bottom: 0;">$ ls posts/</div>
            <a href="/admin/posts/new" class="social-link" style="font-size: 13px;">+ new post</a>
        </div>
        <div class="post-list">
            <a th:each="post : ${posts}"
               th:href="@{/admin/posts/{id}/edit(id=${post.id})}"
               class="post-item">
                <div class="post-title" th:text="${post.title}">Post Title</div>
                <div class="post-meta">
                    <span th:text="${post.published ? 'published' : 'draft'}"
                          th:style="${post.published ? 'color: var(--accent)' : 'color: var(--text-muted)'}">status</span>
                    &mdash; <span th:text="${#temporals.format(post.updatedAt, 'MMM dd, yyyy')}">Mar 28, 2026</span>
                </div>
            </a>
        </div>
    </div>
</body>
</html>
```

- [ ] **Step 3: Create admin post editor template**

Create `src/main/resources/templates/admin/post-editor.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Edit Post</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/easymde/dist/easymde.min.css">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: #fff; border-bottom: 1px solid var(--accent);">Posts</a>
            <a href="/admin/pages" style="color: var(--text-secondary);">Pages</a>
            <a href="/admin/social-links" style="color: var(--text-secondary);">Social Links</a>
            <a href="/admin/settings" style="color: var(--text-secondary);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px;">
        <form th:action="${post.id != null ? '/admin/posts/' + post.id + '/edit' : '/admin/posts/new'}" method="post">
            <div style="display: flex; gap: 12px; margin-bottom: 12px;">
                <div style="flex: 1;">
                    <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Title</label>
                    <input type="text" name="title" th:value="${post.title}" required
                           style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: #fff; padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
                </div>
                <div style="width: 250px;">
                    <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Slug</label>
                    <input type="text" name="slug" th:value="${post.slug}"
                           style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text-secondary); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
                </div>
            </div>
            <div style="margin-bottom: 12px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Tags</label>
                <input type="text" name="tags" th:value="${post.tags}" placeholder="spring-boot, java, web"
                       style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text-secondary); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
            </div>
            <div style="margin-bottom: 12px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Excerpt</label>
                <textarea name="excerpt" rows="2" th:text="${post.excerpt}"
                          style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text-secondary); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px; resize: vertical;"></textarea>
            </div>
            <div style="margin-bottom: 12px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Content (Markdown)</label>
                <textarea id="content-editor" name="content" th:text="${post.content}"
                          style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 12px; font-family: inherit; font-size: 14px; border-radius: 3px; min-height: 300px;"></textarea>
            </div>
            <div style="display: flex; gap: 10px; justify-content: flex-end;">
                <button type="submit" name="action" value="save"
                        style="color: var(--text-secondary); font-size: 13px; border: 1px solid var(--border-light); padding: 6px 16px; border-radius: 3px; background: none; font-family: inherit; cursor: pointer;">
                    save draft
                </button>
                <button type="submit" name="action" value="publish"
                        style="color: var(--bg); font-size: 13px; background: var(--accent); padding: 6px 16px; border-radius: 3px; border: none; font-family: inherit; font-weight: bold; cursor: pointer;">
                    publish
                </button>
            </div>
        </form>

        <div th:if="${post.id != null}" style="margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--border);">
            <form th:action="@{/admin/posts/{id}/delete(id=${post.id})}" method="post"
                  onsubmit="return confirm('Are you sure you want to delete this post?');">
                <button type="submit"
                        style="color: #ff4444; font-size: 12px; background: none; border: 1px solid #ff4444; padding: 4px 12px; border-radius: 3px; font-family: inherit; cursor: pointer;">
                    delete post
                </button>
            </form>
        </div>
    </div>
    <script src="https://cdn.jsdelivr.net/npm/easymde/dist/easymde.min.js"></script>
    <script th:src="@{/js/admin.js}"></script>
</body>
</html>
```

- [ ] **Step 4: Create admin pages list template**

Create `src/main/resources/templates/admin/pages.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Pages</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: var(--text-secondary);">Posts</a>
            <a href="/admin/pages" style="color: #fff; border-bottom: 1px solid var(--accent);">Pages</a>
            <a href="/admin/social-links" style="color: var(--text-secondary);">Social Links</a>
            <a href="/admin/settings" style="color: var(--text-secondary);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
            <div class="section-header" style="margin-bottom: 0;">$ ls pages/</div>
            <a href="/admin/pages/new" class="social-link" style="font-size: 13px;">+ new page</a>
        </div>
        <div class="post-list">
            <a th:each="page : ${pages}"
               th:href="@{/admin/pages/{id}/edit(id=${page.id})}"
               class="post-item">
                <div class="post-title" th:text="${page.title}">Page Title</div>
                <div class="post-meta">
                    <span th:text="${page.published ? 'published' : 'draft'}"
                          th:style="${page.published ? 'color: var(--accent)' : 'color: var(--text-muted)'}">status</span>
                    &mdash; <span th:text="'/pages/' + ${page.slug}">slug</span>
                    &mdash; <span th:text="${#temporals.format(page.updatedAt, 'MMM dd, yyyy')}">Mar 28, 2026</span>
                </div>
            </a>
        </div>
    </div>
</body>
</html>
```

- [ ] **Step 5: Create admin page editor template**

Create `src/main/resources/templates/admin/page-editor.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Edit Page</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/easymde/dist/easymde.min.css">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: var(--text-secondary);">Posts</a>
            <a href="/admin/pages" style="color: #fff; border-bottom: 1px solid var(--accent);">Pages</a>
            <a href="/admin/social-links" style="color: var(--text-secondary);">Social Links</a>
            <a href="/admin/settings" style="color: var(--text-secondary);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px;">
        <form th:action="${page.id != null ? '/admin/pages/' + page.id + '/edit' : '/admin/pages/new'}" method="post">
            <div style="display: flex; gap: 12px; margin-bottom: 12px;">
                <div style="flex: 1;">
                    <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Title</label>
                    <input type="text" name="title" th:value="${page.title}" required
                           style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: #fff; padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
                </div>
                <div style="width: 250px;">
                    <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Slug</label>
                    <input type="text" name="slug" th:value="${page.slug}"
                           style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text-secondary); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
                </div>
            </div>
            <div style="margin-bottom: 12px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Content (Markdown)</label>
                <textarea id="content-editor" name="content" th:text="${page.content}"
                          style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 12px; font-family: inherit; font-size: 14px; border-radius: 3px; min-height: 300px;"></textarea>
            </div>
            <div style="display: flex; gap: 10px; justify-content: flex-end;">
                <button type="submit" name="action" value="save"
                        style="color: var(--text-secondary); font-size: 13px; border: 1px solid var(--border-light); padding: 6px 16px; border-radius: 3px; background: none; font-family: inherit; cursor: pointer;">
                    save draft
                </button>
                <button type="submit" name="action" value="publish"
                        style="color: var(--bg); font-size: 13px; background: var(--accent); padding: 6px 16px; border-radius: 3px; border: none; font-family: inherit; font-weight: bold; cursor: pointer;">
                    publish
                </button>
            </div>
        </form>

        <div th:if="${page.id != null}" style="margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--border);">
            <form th:action="@{/admin/pages/{id}/delete(id=${page.id})}" method="post"
                  onsubmit="return confirm('Are you sure you want to delete this page?');">
                <button type="submit"
                        style="color: #ff4444; font-size: 12px; background: none; border: 1px solid #ff4444; padding: 4px 12px; border-radius: 3px; font-family: inherit; cursor: pointer;">
                    delete page
                </button>
            </form>
        </div>
    </div>
    <script src="https://cdn.jsdelivr.net/npm/easymde/dist/easymde.min.js"></script>
    <script th:src="@{/js/admin.js}"></script>
</body>
</html>
```

- [ ] **Step 6: Create admin social links template**

Create `src/main/resources/templates/admin/social-links.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Social Links</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: var(--text-secondary);">Posts</a>
            <a href="/admin/pages" style="color: var(--text-secondary);">Pages</a>
            <a href="/admin/social-links" style="color: #fff; border-bottom: 1px solid var(--accent);">Social Links</a>
            <a href="/admin/settings" style="color: var(--text-secondary);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px;">
        <div class="section-header" style="margin-bottom: 16px;">$ ls social-links/</div>

        <div class="post-list" th:if="${not #lists.isEmpty(links)}" style="margin-bottom: 24px;">
            <div th:each="link : ${links}" class="post-item" style="display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <span class="post-title" th:text="${link.displayName}">LinkedIn</span>
                    <span class="post-meta" th:text="' — ' + ${link.platform} + ' — ' + ${link.url}">platform — url</span>
                </div>
                <form th:action="@{/admin/social-links/{id}/delete(id=${link.id})}" method="post" style="display:inline;">
                    <button type="submit" style="color: #ff4444; background: none; border: none; font-family: inherit; font-size: 12px; cursor: pointer;">delete</button>
                </form>
            </div>
        </div>

        <div style="border: 1px solid var(--border-light); border-radius: 4px; padding: 16px; margin-top: 16px;">
            <div style="color: var(--accent); font-size: 13px; margin-bottom: 12px;">+ add new link</div>
            <form action="/admin/social-links" method="post" style="display: flex; gap: 10px; flex-wrap: wrap; align-items: flex-end;">
                <div>
                    <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">Platform</label>
                    <input type="text" name="platform" required placeholder="linkedin"
                           style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px; width: 120px;">
                </div>
                <div>
                    <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">Display Name</label>
                    <input type="text" name="displayName" required placeholder="LinkedIn"
                           style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px; width: 140px;">
                </div>
                <div style="flex: 1;">
                    <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">URL</label>
                    <input type="url" name="url" required placeholder="https://linkedin.com/in/..."
                           style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px;">
                </div>
                <div>
                    <label style="color: var(--text-muted); font-size: 10px; text-transform: uppercase; display: block; margin-bottom: 4px;">Order</label>
                    <input type="number" name="sortOrder" value="0"
                           style="background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 6px 10px; font-family: inherit; font-size: 13px; border-radius: 3px; width: 60px;">
                </div>
                <button type="submit"
                        style="color: var(--bg); font-size: 13px; background: var(--accent); padding: 6px 16px; border-radius: 3px; border: none; font-family: inherit; font-weight: bold; cursor: pointer;">
                    add
                </button>
            </form>
        </div>
    </div>
</body>
</html>
```

- [ ] **Step 7: Create admin settings template**

Create `src/main/resources/templates/admin/settings.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin — Settings</title>
    <link rel="stylesheet" th:href="@{/css/terminal.css}">
</head>
<body>
    <nav class="nav">
        <div style="display: flex; gap: 20px; align-items: center;">
            <span class="nav-prompt">admin@neto:~$</span>
            <a href="/admin/posts" style="color: var(--text-secondary);">Posts</a>
            <a href="/admin/pages" style="color: var(--text-secondary);">Pages</a>
            <a href="/admin/social-links" style="color: var(--text-secondary);">Social Links</a>
            <a href="/admin/settings" style="color: #fff; border-bottom: 1px solid var(--accent);">Settings</a>
        </div>
        <a href="/admin/logout" style="color: var(--text-muted); font-size: 12px;">logout</a>
    </nav>
    <div style="padding: 24px 32px; max-width: 600px;">
        <div class="section-header" style="margin-bottom: 16px;">$ vi site.conf</div>

        <div th:if="${saved}" style="color: var(--accent); margin-bottom: 16px; font-size: 13px;">Settings saved.</div>

        <form action="/admin/settings" method="post" enctype="multipart/form-data">
            <div style="margin-bottom: 16px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Site Name</label>
                <input type="text" name="siteName" th:value="${siteName}" required
                       style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
            </div>
            <div style="margin-bottom: 16px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Tagline</label>
                <input type="text" name="siteTagline" th:value="${siteTagline}"
                       style="width: 100%; background: var(--surface); border: 1px solid var(--border-light); color: var(--text); padding: 8px 12px; font-family: inherit; font-size: 14px; border-radius: 3px;">
            </div>
            <div style="margin-bottom: 16px;">
                <label style="color: var(--text-muted); font-size: 11px; text-transform: uppercase; display: block; margin-bottom: 4px;">Photo</label>
                <div th:if="${photoPath != null and !photoPath.isEmpty()}" style="margin-bottom: 8px;">
                    <img th:src="@{${photoPath}}" style="width: 80px; height: 80px; border-radius: 50%; border: 1px solid var(--border-light);">
                </div>
                <input type="file" name="photo" accept="image/*"
                       style="color: var(--text-secondary); font-family: inherit; font-size: 13px;">
            </div>
            <button type="submit"
                    style="color: var(--bg); font-size: 14px; background: var(--accent); padding: 8px 24px; border-radius: 3px; border: none; font-family: inherit; font-weight: bold; cursor: pointer;">
                save settings
            </button>
        </form>
    </div>
</body>
</html>
```

- [ ] **Step 8: Create admin.js for EasyMDE initialization**

Create `src/main/resources/static/js/admin.js`:

```javascript
document.addEventListener('DOMContentLoaded', function() {
    var textarea = document.getElementById('content-editor');
    if (textarea) {
        new EasyMDE({
            element: textarea,
            spellChecker: false,
            status: false,
            theme: 'dark',
            minHeight: '300px',
            autosave: {
                enabled: false
            },
            toolbar: [
                'bold', 'italic', 'heading', '|',
                'code', 'quote', 'unordered-list', 'ordered-list', '|',
                'link', 'image', '|',
                'preview', 'side-by-side', 'fullscreen', '|',
                'guide'
            ]
        });
    }
});
```

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/templates/admin/ src/main/resources/static/js/admin.js
git commit -m "feat: add admin panel templates with EasyMDE Markdown editor"
```

---

### Task 14: Admin Controller

**Files:**
- Create: `src/main/java/com/davidneto/homepage/controller/AdminController.java`
- Create: `src/test/java/com/davidneto/homepage/controller/AdminControllerTest.java`

- [ ] **Step 1: Write AdminController test**

Create `src/test/java/com/davidneto/homepage/controller/AdminControllerTest.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BlogPostService blogPostService;

    @MockBean
    private StaticPageService staticPageService;

    @MockBean
    private SocialLinkService socialLinkService;

    @MockBean
    private SiteConfigService siteConfigService;

    @Test
    void adminPosts_redirectsToLoginWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/admin/posts"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPosts_returnsPostList() throws Exception {
        when(blogPostService.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/posts"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/posts"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPages_returnsPageList() throws Exception {
        when(staticPageService.getAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/pages"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/pages"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPost_savesAndRedirects() throws Exception {
        BlogPost saved = new BlogPost();
        saved.setId(1L);
        when(blogPostService.save(any())).thenReturn(saved);

        mockMvc.perform(post("/admin/posts/new")
                        .with(csrf())
                        .param("title", "New Post")
                        .param("content", "# Hello")
                        .param("action", "save"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts/1/edit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createPage_savesAndRedirects() throws Exception {
        StaticPage saved = new StaticPage();
        saved.setId(1L);
        when(staticPageService.save(any())).thenReturn(saved);

        mockMvc.perform(post("/admin/pages/new")
                        .with(csrf())
                        .param("title", "Privacy Policy")
                        .param("content", "# Privacy")
                        .param("action", "save"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pages/1/edit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePost_deletesAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/posts/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/posts"));

        verify(blogPostService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deletePage_deletesAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/pages/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pages"));

        verify(staticPageService).delete(1L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest=AdminControllerTest`
Expected: FAIL — class not found

- [ ] **Step 3: Implement AdminController**

Create `src/main/java/com/davidneto/homepage/controller/AdminController.java`:

```java
package com.davidneto.homepage.controller;

import com.davidneto.homepage.entity.BlogPost;
import com.davidneto.homepage.entity.SocialLink;
import com.davidneto.homepage.entity.StaticPage;
import com.davidneto.homepage.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final BlogPostService blogPostService;
    private final StaticPageService staticPageService;
    private final SocialLinkService socialLinkService;
    private final SiteConfigService siteConfigService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public AdminController(BlogPostService blogPostService,
                           StaticPageService staticPageService,
                           SocialLinkService socialLinkService,
                           SiteConfigService siteConfigService) {
        this.blogPostService = blogPostService;
        this.staticPageService = staticPageService;
        this.socialLinkService = socialLinkService;
        this.siteConfigService = siteConfigService;
    }

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    // === Posts ===

    @GetMapping("/posts")
    public String posts(Model model) {
        model.addAttribute("posts", blogPostService.getAll());
        return "admin/posts";
    }

    @GetMapping("/posts/new")
    public String newPost(Model model) {
        model.addAttribute("post", new BlogPost());
        return "admin/post-editor";
    }

    @GetMapping("/posts/{id}/edit")
    public String editPost(@PathVariable Long id, Model model) {
        BlogPost post = blogPostService.getById(id).orElseThrow();
        model.addAttribute("post", post);
        return "admin/post-editor";
    }

    @PostMapping("/posts/new")
    public String createPost(@RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String tags,
                             @RequestParam(required = false) String excerpt,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        BlogPost post = new BlogPost();
        post.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            post.setSlug(slug);
        }
        post.setTags(tags);
        post.setExcerpt(excerpt);
        post.setContent(content);
        BlogPost saved = blogPostService.save(post);
        if ("publish".equals(action)) {
            blogPostService.publish(saved.getId());
        }
        return "redirect:/admin/posts/" + saved.getId() + "/edit";
    }

    @PostMapping("/posts/{id}/edit")
    public String updatePost(@PathVariable Long id,
                             @RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String tags,
                             @RequestParam(required = false) String excerpt,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        BlogPost post = blogPostService.getById(id).orElseThrow();
        post.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            post.setSlug(slug);
        }
        post.setTags(tags);
        post.setExcerpt(excerpt);
        post.setContent(content);
        blogPostService.save(post);
        if ("publish".equals(action)) {
            blogPostService.publish(id);
        }
        return "redirect:/admin/posts/" + id + "/edit";
    }

    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id) {
        blogPostService.delete(id);
        return "redirect:/admin/posts";
    }

    // === Pages ===

    @GetMapping("/pages")
    public String pages(Model model) {
        model.addAttribute("pages", staticPageService.getAll());
        return "admin/pages";
    }

    @GetMapping("/pages/new")
    public String newPage(Model model) {
        model.addAttribute("page", new StaticPage());
        return "admin/page-editor";
    }

    @GetMapping("/pages/{id}/edit")
    public String editPage(@PathVariable Long id, Model model) {
        StaticPage page = staticPageService.getById(id).orElseThrow();
        model.addAttribute("page", page);
        return "admin/page-editor";
    }

    @PostMapping("/pages/new")
    public String createPage(@RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        StaticPage page = new StaticPage();
        page.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            page.setSlug(slug);
        }
        page.setContent(content);
        if ("publish".equals(action)) {
            page.setPublished(true);
        }
        StaticPage saved = staticPageService.save(page);
        return "redirect:/admin/pages/" + saved.getId() + "/edit";
    }

    @PostMapping("/pages/{id}/edit")
    public String updatePage(@PathVariable Long id,
                             @RequestParam String title,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String content,
                             @RequestParam String action) {
        StaticPage page = staticPageService.getById(id).orElseThrow();
        page.setTitle(title);
        if (slug != null && !slug.isBlank()) {
            page.setSlug(slug);
        }
        page.setContent(content);
        if ("publish".equals(action)) {
            page.setPublished(true);
        } else {
            page.setPublished(false);
        }
        staticPageService.save(page);
        return "redirect:/admin/pages/" + id + "/edit";
    }

    @PostMapping("/pages/{id}/delete")
    public String deletePage(@PathVariable Long id) {
        staticPageService.delete(id);
        return "redirect:/admin/pages";
    }

    // === Social Links ===

    @GetMapping("/social-links")
    public String socialLinks(Model model) {
        model.addAttribute("links", socialLinkService.getAllSorted());
        return "admin/social-links";
    }

    @PostMapping("/social-links")
    public String addSocialLink(@RequestParam String platform,
                                @RequestParam String displayName,
                                @RequestParam String url,
                                @RequestParam(defaultValue = "0") int sortOrder) {
        SocialLink link = new SocialLink();
        link.setPlatform(platform);
        link.setDisplayName(displayName);
        link.setUrl(url);
        link.setSortOrder(sortOrder);
        socialLinkService.save(link);
        return "redirect:/admin/social-links";
    }

    @PostMapping("/social-links/{id}/delete")
    public String deleteSocialLink(@PathVariable Long id) {
        socialLinkService.delete(id);
        return "redirect:/admin/social-links";
    }

    // === Settings ===

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        return "admin/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam String siteName,
                               @RequestParam(required = false) String siteTagline,
                               @RequestParam(required = false) MultipartFile photo,
                               Model model) throws IOException {
        siteConfigService.set("site.name", siteName);
        siteConfigService.set("site.tagline", siteTagline != null ? siteTagline : "");

        if (photo != null && !photo.isEmpty()) {
            Path uploadPath = Paths.get(uploadDir);
            Files.createDirectories(uploadPath);
            String filename = "photo" + getExtension(photo.getOriginalFilename());
            Path filePath = uploadPath.resolve(filename);
            photo.transferTo(filePath.toFile());
            siteConfigService.set("site.photo_path", "/uploads/" + filename);
        }

        model.addAttribute("saved", true);
        model.addAttribute("siteName", siteConfigService.get("site.name"));
        model.addAttribute("siteTagline", siteConfigService.get("site.tagline"));
        model.addAttribute("photoPath", siteConfigService.get("site.photo_path"));
        return "admin/settings";
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest=AdminControllerTest`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/davidneto/homepage/controller/AdminController.java src/test/java/com/davidneto/homepage/controller/AdminControllerTest.java
git commit -m "feat: add admin controller with CRUD for posts, pages, social links, settings"
```

---

### Task 15: Static File Serving for Uploads

**Files:**
- Create: `src/main/java/com/davidneto/homepage/config/WebConfig.java`

- [ ] **Step 1: Create WebConfig to serve uploaded files**

Create `src/main/java/com/davidneto/homepage/config/WebConfig.java`:

```java
package com.davidneto.homepage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/davidneto/homepage/config/WebConfig.java
git commit -m "feat: add static file serving for photo uploads"
```

---

### Task 16: Docker and Deployment Configuration

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `Caddyfile`
- Create: `.env.example`

- [ ] **Step 1: Create Dockerfile**

Create `Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:resolve
COPY src src
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /app/uploads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create docker-compose.yml**

Create `docker-compose.yml`:

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
    volumes:
      - uploads:/app/uploads
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
```

- [ ] **Step 3: Create Caddyfile**

Create `Caddyfile`:

```
:80 {
    reverse_proxy app:8080
}

# When you have a domain, replace the block above with:
# yourdomain.com {
#     reverse_proxy app:8080
# }
# Caddy will automatically provision HTTPS via Let's Encrypt.
```

- [ ] **Step 4: Create .env.example**

Create `.env.example`:

```
POSTGRES_DB=homepage
POSTGRES_USER=homepage
POSTGRES_PASSWORD=changeme
ADMIN_USERNAME=admin
ADMIN_PASSWORD=changeme
```

- [ ] **Step 5: Commit**

```bash
git add Dockerfile docker-compose.yml Caddyfile .env.example
git commit -m "feat: add Docker Compose deployment with Caddy, PostgreSQL, and Spring Boot"
```

---

### Task 17: Run Full Test Suite and Verify

- [ ] **Step 1: Run all tests**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Verify Docker build works**

Run: `docker compose build app`
Expected: Build completes successfully

- [ ] **Step 3: Commit any remaining changes**

Run: `git status`
If any uncommitted changes remain, stage and commit them.
