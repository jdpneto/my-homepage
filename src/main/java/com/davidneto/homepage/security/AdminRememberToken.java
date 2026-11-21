package com.davidneto.homepage.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A remembered admin browser. Only the SHA-256 of the token is stored; the series is the key. */
@Entity
@Table(name = "admin_remember_token")
public class AdminRememberToken {
    @Id
    @Column(length = 64) private String series;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(nullable = false, length = 100) private String username;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_used_at", nullable = false) private Instant lastUsedAt;

    public String getSeries() { return series; }
    public void setSeries(String v) { this.series = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant v) { this.lastUsedAt = v; }
}
