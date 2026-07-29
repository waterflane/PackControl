package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

public record PackFileCandidate(
        URI downloadUri,
        String sourceId,
        String fileName,
        long advertisedSize,
        Hashes advertisedHashes,
        Set<String> allowedDomains
) {
    public PackFileCandidate {
        Objects.requireNonNull(downloadUri, "downloadUri");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(fileName, "fileName");
        allowedDomains = Set.copyOf(allowedDomains);
    }
}
