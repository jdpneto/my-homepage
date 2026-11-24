package com.davidneto.homepage.cameras;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Settings under {@code app.cameras}. */
@ConfigurationProperties(prefix = "app.cameras")
public class CameraProperties {
    /** The camera volume as seen by the app (WebDAV root of user "cameras"). */
    private Path rootDir = Path.of("./cameras");
    /** Thumbnails; never under rootDir (would count against the quota and show in WebDAV). */
    private Path thumbsDir = Path.of("./camera-thumbs");
    private int retentionDays = 14;
    /** Disk safety net: above this % used (volume or quota), the oldest non-kept clips go... */
    private int safetyNetHighPercent = 85;
    /** ...until both are below this %. */
    private int safetyNetLowPercent = 75;
    private String ntfyUrl = "https://ntfy.sh";
    /** Blank = alerts disabled. */
    private String ntfyTopic = "";
    private int warnPercent = 85;
    private int rootDiskWarnPercent = 90;
    private Duration heartbeatMaxAge = Duration.ofMinutes(60);
    private Duration hubDownMaxAge = Duration.ofMinutes(60);
    private Duration reminderEvery = Duration.ofHours(24);
    /** WebDAV user whose quota is the camera quota. */
    private String webdavUser = "cameras";
    /** Public base URL of this site, for links in pushes (no trailing slash needed). */
    private String publicUrl = "https://davidneto.eu";
    /** Path used to measure the VPS root disk. */
    private Path rootDiskPath = Path.of("/");

    public Path getRootDir() { return rootDir; }
    public void setRootDir(Path v) { this.rootDir = v; }
    public Path getThumbsDir() { return thumbsDir; }
    public void setThumbsDir(Path v) { this.thumbsDir = v; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }
    public int getSafetyNetHighPercent() { return safetyNetHighPercent; }
    public void setSafetyNetHighPercent(int v) { this.safetyNetHighPercent = v; }
    public int getSafetyNetLowPercent() { return safetyNetLowPercent; }
    public void setSafetyNetLowPercent(int v) { this.safetyNetLowPercent = v; }
    public String getNtfyUrl() { return ntfyUrl; }
    public void setNtfyUrl(String v) { this.ntfyUrl = v; }
    public String getNtfyTopic() { return ntfyTopic; }
    public void setNtfyTopic(String v) { this.ntfyTopic = v; }
    public int getWarnPercent() { return warnPercent; }
    public void setWarnPercent(int v) { this.warnPercent = v; }
    public int getRootDiskWarnPercent() { return rootDiskWarnPercent; }
    public void setRootDiskWarnPercent(int v) { this.rootDiskWarnPercent = v; }
    public Duration getHeartbeatMaxAge() { return heartbeatMaxAge; }
    public void setHeartbeatMaxAge(Duration v) { this.heartbeatMaxAge = v; }
    public Duration getHubDownMaxAge() { return hubDownMaxAge; }
    public void setHubDownMaxAge(Duration v) { this.hubDownMaxAge = v; }
    public Duration getReminderEvery() { return reminderEvery; }
    public void setReminderEvery(Duration v) { this.reminderEvery = v; }
    public String getWebdavUser() { return webdavUser; }
    public void setWebdavUser(String v) { this.webdavUser = v; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String v) { this.publicUrl = v; }
    public Path getRootDiskPath() { return rootDiskPath; }
    public void setRootDiskPath(Path v) { this.rootDiskPath = v; }
}
