package com.davidneto.homepage.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Writes are bulk statements so parallel requests from one phone never race on an entity
 * (a second delete or update of the same row simply affects 0 rows).
 */
public interface AdminRememberTokenRepository extends JpaRepository<AdminRememberToken, String> {

    @Modifying
    @Transactional
    @Query("delete from AdminRememberToken t where t.series = :series")
    int deleteBySeries(@Param("series") String series);

    @Modifying
    @Transactional
    @Query("delete from AdminRememberToken t where t.lastUsedAt < :cutoff")
    int deleteIdleSince(@Param("cutoff") Instant cutoff);

    @Modifying
    @Transactional
    @Query("delete from AdminRememberToken t")
    int deleteEvery();

    /** Rolls last_used_at forward unless it was already done after {@code touchedAfter}. */
    @Modifying
    @Transactional
    @Query("update AdminRememberToken t set t.lastUsedAt = :now where t.series = :series and t.lastUsedAt < :touchedAfter")
    int touch(@Param("series") String series, @Param("now") Instant now, @Param("touchedAfter") Instant touchedAfter);
}
