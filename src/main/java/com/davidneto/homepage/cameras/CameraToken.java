package com.davidneto.homepage.cameras;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An app login. Only the SHA-256 of the token is stored. */
@Entity
@Table(name = "camera_token")
public class CameraToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(length = 100) private String label;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    public Long getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant v) { this.lastUsedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
