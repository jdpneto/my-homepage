package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertMuteTest {

    @Autowired CameraSettingRepository settings;
    AlertCheckerTest.MutableClock clock;
    AlertMute mute;

    @BeforeEach
    void setUp() {
        settings.deleteAll();
        clock = new AlertCheckerTest.MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
        mute = new AlertMute(settings, clock);
    }

    @Test
    void notMutedByDefault() {
        assertThat(mute.get().muted()).isFalse();
    }

    @Test
    void muteForever() {
        mute.set(true, null);
        clock.advance(Duration.ofDays(30));
        assertThat(mute.get()).isEqualTo(new AlertMute.MuteState(true, null));
        assertThat(new AlertMute(settings, clock).get().muted()).isTrue(); // survives restart
    }

    @Test
    void muteUntilExpires() {
        mute.set(true, clock.instant().plus(Duration.ofHours(8)));
        clock.advance(Duration.ofHours(7));
        assertThat(mute.get().muted()).isTrue();
        clock.advance(Duration.ofHours(2));
        assertThat(mute.get().muted()).isFalse();
    }

    @Test
    void unmute() {
        mute.set(true, null);
        mute.set(false, null);
        assertThat(mute.get().muted()).isFalse();
    }
}
