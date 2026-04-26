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
