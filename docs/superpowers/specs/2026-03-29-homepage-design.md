# Personal Homepage — Design Specification

## Overview

A personal homepage and blog hosted on a Hetzner Cloud VPS, built with Spring Boot and Thymeleaf. Features a terminal-inspired black-and-white design, a blog with a web-based Markdown editor, arbitrary static pages managed through an admin panel, and social media links.

## Architecture

### Stack

- **Backend:** Spring Boot 3, Spring MVC, Spring Security, Spring Data JPA
- **Templating:** Thymeleaf (server-side rendered)
- **Database:** PostgreSQL (Docker container with persistent named volume)
- **Migrations:** Flyway
- **Reverse Proxy:** Caddy (automatic HTTPS when domain is added)
- **Deployment:** Docker Compose on Hetzner Cloud VPS
- **Markdown Rendering:** commonmark-java (server-side) + EasyMDE (client-side editor in admin)

### Container Layout

```
docker-compose.yml
├── caddy        (reverse proxy, port 80/443 → app:8080)
├── app          (Spring Boot jar, port 8080)
└── postgres     (PostgreSQL 16, named volume: pgdata)
```

Caddy serves as the entry point, forwarding requests to the Spring Boot app. When no domain is configured, Caddy runs in HTTP-only mode on port 80. When a domain is added later, Caddy handles automatic HTTPS via Let's Encrypt with zero additional configuration.

## Data Model

### BlogPost

| Column       | Type          | Notes                                      |
|-------------|---------------|---------------------------------------------|
| id          | BIGINT PK     | Auto-generated                              |
| title       | VARCHAR(255)  | Required                                    |
| slug        | VARCHAR(255)  | Unique, auto-generated from title           |
| content     | TEXT          | Raw Markdown                                |
| excerpt     | VARCHAR(500)  | Short summary for listing page              |
| tags        | VARCHAR(500)  | Comma-separated (e.g. "spring-boot, java")  |
| published   | BOOLEAN       | Default false (draft)                       |
| created_at  | TIMESTAMP     | Set on creation                             |
| updated_at  | TIMESTAMP     | Updated on save                             |
| published_at| TIMESTAMP     | Set when first published, used for ordering |

### StaticPage

| Column      | Type          | Notes                                      |
|------------|---------------|---------------------------------------------|
| id         | BIGINT PK     | Auto-generated                              |
| title      | VARCHAR(255)  | Required                                    |
| slug       | VARCHAR(255)  | Unique, becomes URL: /pages/{slug}          |
| content    | TEXT          | Raw Markdown                                |
| published  | BOOLEAN       | Default false (draft)                       |
| created_at | TIMESTAMP     | Set on creation                             |
| updated_at | TIMESTAMP     | Updated on save                             |

Use cases: app privacy policies, terms of service, about pages, project descriptions — any standalone page.

### SocialLink

| Column       | Type          | Notes                                    |
|-------------|---------------|-------------------------------------------|
| id          | BIGINT PK     | Auto-generated                            |
| platform    | VARCHAR(50)   | e.g. "linkedin", "github", "twitter"     |
| url         | VARCHAR(500)  | Full URL to profile                       |
| display_name| VARCHAR(100)  | Label shown on the homepage               |
| icon        | VARCHAR(50)   | Icon identifier (Simple Icons or similar) |
| sort_order  | INT           | Controls display order on homepage        |

### SiteConfig

| Column     | Type          | Notes                                     |
|-----------|---------------|-------------------------------------------|
| id        | BIGINT PK     | Auto-generated                             |
| key       | VARCHAR(100)  | Unique, e.g. "site.name", "site.tagline"  |
| value     | TEXT          | The config value                            |
| updated_at| TIMESTAMP     | Updated on save                            |

Expected keys: `site.name`, `site.tagline`, `site.photo_path`. Photo is stored as a file on disk (inside a mounted volume), path saved in config.

## URL Structure

### Public Routes

| Route               | Description                         |
|---------------------|-------------------------------------|
| `/`                 | Homepage — photo, bio, social links, recent posts |
| `/blog`             | Blog listing — paginated, newest first |
| `/blog/{slug}`      | Individual blog post (rendered Markdown) |
| `/pages/{slug}`     | Static page (rendered Markdown)     |

### Admin Routes (authenticated)

| Route                        | Description                              |
|------------------------------|------------------------------------------|
| `/admin/login`               | Login form                               |
| `/admin/posts`               | Blog post list + editor                  |
| `/admin/posts/new`           | Create new blog post                     |
| `/admin/posts/{id}/edit`     | Edit existing blog post                  |
| `/admin/posts/{id}/delete`   | Delete blog post (POST, with confirmation) |
| `/admin/pages`               | Static page list + editor                |
| `/admin/pages/new`           | Create new static page                   |
| `/admin/pages/{id}/edit`     | Edit existing static page                |
| `/admin/pages/{id}/delete`   | Delete static page (POST, with confirmation) |
| `/admin/social-links`        | Manage social media links                |
| `/admin/settings`            | Site-wide settings (name, tagline, photo)|

## Admin Panel

### Layout

- **Top navigation:** tabs for Posts, Pages, Social Links, Settings
- **Posts tab:** left sidebar lists all posts (title, status, date), clicking opens the editor on the right
- **Pages tab:** identical layout to Posts, but for static pages (no tags/excerpt fields)
- **Social Links tab:** list of links with add/edit/delete/reorder
- **Settings tab:** form fields for site name, tagline, photo upload

### Post/Page Editor

- **Title field:** text input, slug auto-generates on blur (editable)
- **Tags field:** (posts only) comma-separated text input
- **Excerpt field:** (posts only) short text area
- **Content field:** EasyMDE Markdown editor with live preview toggle
- **Actions:** Save Draft, Publish, Delete (with confirmation prompt)
- **CRUD operations:** Create new posts/pages, edit existing ones, delete posts/pages permanently
- Markdown is stored raw in the database and rendered to HTML with commonmark-java on page view

## Visual Design

### Theme: Terminal Console

- **Background:** #0a0a0a (near-black)
- **Primary text:** #d4d4d4 (light gray)
- **Secondary text:** #888888 (mid gray)
- **Muted text:** #555555 (dark gray)
- **Accent:** #00ff00 (terminal green)
- **Borders:** #222222 – #333333
- **Font:** monospace throughout (system monospace stack: `'Courier New', Courier, monospace`)

### Homepage

- **Nav bar:** terminal prompt as logo (`david@neto:~$`), minimal text links (home, blog, pages)
- **Hero section:** circular photo (120px), name in large text, tagline prefixed with `>`, social link buttons styled as bordered terminal chips
- **Recent posts:** section headed with `~/blog $ ls -lt | head`, post entries in a bordered list showing title, date, tags
- **Footer:** minimal — license, "Built with Spring Boot"

### Blog Listing

- Shell command header: `~/blog $ find . -name "*.md" | sort -r`
- Each post: title (white), date + tags (gray), excerpt (mid-gray)
- Paginated with terminal-style navigation

### Blog Post

- Shell command header: `~/blog $ cat {slug}.md`
- Rendered Markdown with styled headings, code blocks (dark background, bordered), inline code in green
- Previous/next post navigation at bottom

### Static Pages

- Same rendering as blog posts
- Shell command header: `~/ $ cat pages/{slug}.md`

### Responsive Design

- Mobile: hero stacks vertically (photo on top, centered), nav collapses to hamburger, social links use abbreviations
- Content areas go full-width on small screens

## Authentication

- Single admin user, configured via environment variables (`ADMIN_USERNAME`, `ADMIN_PASSWORD`)
- Spring Security form-based login at `/admin/login`
- Session-based authentication
- All `/admin/**` routes require authentication
- CSRF protection enabled
- Password stored as BCrypt hash (hashed at startup from env var, or pre-hashed value in env)

## Deployment

### Docker Compose Configuration

Three services:
1. **caddy** — reverse proxy, ports 80 and 443 exposed, Caddyfile mounted
2. **app** — Spring Boot application, port 8080 internal only, depends on postgres
3. **postgres** — PostgreSQL 16, port 5432 internal only, named volume `pgdata` for persistence

### Deployment Steps (Hetzner VPS)

1. Provision a Hetzner Cloud VPS (CX22 or similar, Ubuntu)
2. SSH into the server, install Docker and Docker Compose
3. Clone the repository
4. Create a `.env` file with database credentials and admin credentials
5. Run `docker compose up -d`
6. Access the site at `http://<vps-ip>`
7. (Future) Point a domain's A record to the VPS IP, update Caddyfile with the domain, restart Caddy — HTTPS is automatic

### Environment Variables

| Variable          | Description                    |
|-------------------|--------------------------------|
| POSTGRES_DB       | Database name                  |
| POSTGRES_USER     | Database user                  |
| POSTGRES_PASSWORD | Database password              |
| ADMIN_USERNAME    | Admin panel login username     |
| ADMIN_PASSWORD    | Admin panel login password     |

## Future Considerations (Out of Scope)

- Custom domain + DNS setup (documented as a future step)
- RSS feed for blog
- Full-text search
- Image uploads within blog posts
- SEO meta tags / Open Graph
- Analytics
