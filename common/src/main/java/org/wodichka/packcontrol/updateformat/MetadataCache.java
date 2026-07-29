package org.wodichka.packcontrol.updateformat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class MetadataCache<T> {
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();

    MetadataCache(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    MetadataCache(Duration ttl, Clock clock) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    Optional<T> get(String key) {
        Entry<T> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt.isAfter(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    void put(String key, T value) {
        entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    private record Entry<T>(T value, Instant expiresAt) {
    }
}
