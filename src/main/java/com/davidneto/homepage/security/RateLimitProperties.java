package com.davidneto.homepage.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        int ipMaxFailures,
        int ipWindowSeconds,
        int userLockoutThreshold,
        int userLockoutShortSeconds,
        int userLockoutLongThreshold,
        int userLockoutLongSeconds,
        int maxEntries,
        int sweepIntervalSeconds
) {
    public RateLimitProperties {
        if (ipMaxFailures <= 0) throw new IllegalArgumentException("ipMaxFailures must be > 0");
        if (ipWindowSeconds <= 0) throw new IllegalArgumentException("ipWindowSeconds must be > 0");
        if (userLockoutThreshold <= 0) throw new IllegalArgumentException("userLockoutThreshold must be > 0");
        if (userLockoutLongThreshold < userLockoutThreshold)
            throw new IllegalArgumentException("userLockoutLongThreshold must be >= userLockoutThreshold");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        if (sweepIntervalSeconds <= 0) throw new IllegalArgumentException("sweepIntervalSeconds must be > 0");
    }
}
