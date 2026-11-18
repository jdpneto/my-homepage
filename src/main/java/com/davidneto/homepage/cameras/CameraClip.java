package com.davidneto.homepage.cameras;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One indexed clip. Rebuildable from the volume; {@code kept} mirrors the .keep file. */
@Entity
@Table(name = "camera_clip")
public class CameraClip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 512) private String stem;
    @Column(name = "camera_name", nullable = false, length = 200) private String cameraName;
    @Column(name = "camera_id", nullable = false, length = 100) private String cameraId;
    @Column(nullable = false) private int channel;
    @Column(name = "start_at", nullable = false) private Instant startAt;
    @Column(name = "utc_offset_seconds", nullable = false) private int utcOffsetSeconds;
    @Column(name = "end_at", nullable = false) private Instant endAt;
    @Column(name = "duration_seconds", nullable = false) private int durationSeconds;
    @Column(nullable = false) private String triggers;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(nullable = false) private boolean kept;
    @Column(name = "kept_at") private Instant keptAt;
    @Column(name = "thumb_path", length = 512) private String thumbPath;
    @Column(name = "indexed_at", nullable = false) private Instant indexedAt;

    public Long getId() { return id; }
    public String getStem() { return stem; }
    public void setStem(String v) { this.stem = v; }
    public String getCameraName() { return cameraName; }
    public void setCameraName(String v) { this.cameraName = v; }
    public String getCameraId() { return cameraId; }
    public void setCameraId(String v) { this.cameraId = v; }
    public int getChannel() { return channel; }
    public void setChannel(int v) { this.channel = v; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant v) { this.startAt = v; }
    public int getUtcOffsetSeconds() { return utcOffsetSeconds; }
    public void setUtcOffsetSeconds(int v) { this.utcOffsetSeconds = v; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant v) { this.endAt = v; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int v) { this.durationSeconds = v; }
    public String getTriggers() { return triggers; }
    public void setTriggers(String v) { this.triggers = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public boolean isKept() { return kept; }
    public void setKept(boolean v) { this.kept = v; }
    public Instant getKeptAt() { return keptAt; }
    public void setKeptAt(Instant v) { this.keptAt = v; }
    public String getThumbPath() { return thumbPath; }
    public void setThumbPath(String v) { this.thumbPath = v; }
    public Instant getIndexedAt() { return indexedAt; }
    public void setIndexedAt(Instant v) { this.indexedAt = v; }
}
