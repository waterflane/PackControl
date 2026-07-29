package org.wodichka.packcontrol.updateformat;

import java.time.Duration;
import java.util.Objects;

public record PackHttpPolicy(
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRedirects,
        int maxAttempts,
        Duration retryDelay,
        String userAgent
) {
    public PackHttpPolicy {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retryDelay, "retryDelay");
        Objects.requireNonNull(userAgent, "userAgent");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must not be negative");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
    }

    public static PackHttpPolicy defaults() {
        return new PackHttpPolicy(
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                3,
                3,
                Duration.ofMillis(200),
                "PackControl/0.1.0 (Minecraft modpack updater; +https://github.com/waterflane/PackControl)"
        );
    }
}
