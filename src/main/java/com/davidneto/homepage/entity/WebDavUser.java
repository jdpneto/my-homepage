package com.davidneto.homepage.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "webdav_users")
public class WebDavUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String u) { this.username = u; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public Instant getCreatedAt() { return createdAt; }
}
