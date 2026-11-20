package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CameraRepositoriesIT {

    @Autowired CameraClipRepository clips;
    @Autowired CameraTokenRepository tokens;
    @Autowired AlertStateRepository alerts;

    @org.junit.jupiter.api.BeforeEach
    void empty() {
        // In batch: a plain deleteAll() inside this test's transaction is flushed after the
        // test's inserts (Hibernate runs inserts before deletes), so a leftover stem collides.
        clips.deleteAllInBatch();
        tokens.deleteAll();
        alerts.deleteAll();
    }

    @Test
    void savesAndFindsClipByStem() {
        CameraClip c = new CameraClip();
        c.setStem("Garage/2026-11-18/21-45-49");
        c.setCameraName("Garage");
        c.setCameraId("9527000O0NWN1LLO");
        c.setChannel(0);
        c.setStartAt(Instant.parse("2026-11-18T20:45:49Z"));
        c.setUtcOffsetSeconds(3600);
        c.setEndAt(Instant.parse("2026-11-18T20:45:59Z"));
        c.setDurationSeconds(10);
        c.setTriggers("person");
        c.setSizeBytes(1224370);
        c.setIndexedAt(Instant.now());
        clips.save(c);
        CameraClip found = clips.findByStem("Garage/2026-11-18/21-45-49").orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.isKept()).isFalse();
        assertThat(found.getUtcOffsetSeconds()).isEqualTo(3600);
    }

    @Test
    void findsTokenByHash() {
        CameraToken t = new CameraToken();
        t.setTokenHash("a".repeat(64));
        t.setLabel("iPhone");
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plusSeconds(3600));
        tokens.save(t);
        assertThat(tokens.findByTokenHash("a".repeat(64))).isPresent();
    }

    @Test
    void savesAlertState() {
        AlertState a = new AlertState();
        a.setAlertKey("disk_low");
        a.setActive(true);
        a.setSince(Instant.now());
        alerts.save(a);
        assertThat(alerts.findById("disk_low")).hasValueSatisfying(s -> assertThat(s.isActive()).isTrue());
    }
}
