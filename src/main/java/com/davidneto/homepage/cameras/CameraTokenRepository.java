package com.davidneto.homepage.cameras;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CameraTokenRepository extends JpaRepository<CameraToken, Long> {
    Optional<CameraToken> findByTokenHash(String tokenHash);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    long deleteByExpiresAtBefore(java.time.Instant cutoff);
}
