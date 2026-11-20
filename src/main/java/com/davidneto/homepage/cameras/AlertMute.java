package com.davidneto.homepage.cameras;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Alerts on/off, optionally muted until a time. Stored, so it survives restarts. */
@Component
public class AlertMute {

    public record MuteState(boolean muted, Instant until) {
    }

    private static final String MUTED = "alerts_muted";
    private static final String UNTIL = "alerts_muted_until";

    private final CameraSettingRepository settings;
    private final Clock clock;

    public AlertMute(CameraSettingRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public MuteState get() {
        boolean muted = settings.findById(MUTED).map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
        Instant until = settings.findById(UNTIL).map(CameraSetting::getValue)
                .filter(v -> !v.isBlank()).map(Instant::parse).orElse(null);
        if (muted && until != null && !until.isAfter(clock.instant())) return new MuteState(false, null);
        return muted ? new MuteState(true, until) : new MuteState(false, null);
    }

    public MuteState set(boolean muted, Instant until) {
        settings.save(new CameraSetting(MUTED, Boolean.toString(muted)));
        settings.save(new CameraSetting(UNTIL, muted && until != null ? until.toString() : ""));
        return get();
    }
}
