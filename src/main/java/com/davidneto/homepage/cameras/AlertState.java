package com.davidneto.homepage.cameras;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Whether an alert is currently firing, so start/resolve/reminder pushes are sent once. */
@Entity
@Table(name = "camera_alert_state")
public class AlertState {
    @Id
    @Column(name = "alert_key", length = 64) private String alertKey;
    @Column(nullable = false) private boolean active;
    private Instant since;
    @Column(name = "last_notified_at") private Instant lastNotifiedAt;
    @Column(length = 500) private String detail;

    public String getAlertKey() { return alertKey; }
    public void setAlertKey(String v) { this.alertKey = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public Instant getSince() { return since; }
    public void setSince(Instant v) { this.since = v; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(Instant v) { this.lastNotifiedAt = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
}
