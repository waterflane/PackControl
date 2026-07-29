package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.util.Objects;

public record PackFileRequest(
        String requestId,
        String path,
        Hashes hashes,
        long size,
        PackSourceReference source
) {
    public PackFileRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(hashes, "hashes");
        Objects.requireNonNull(source, "source");
    }
}
