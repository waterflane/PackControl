package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record InstalledPackState(
        int schemaVersion,
        String packId,
        String packVersion,
        String releaseId,
        String manifestSha256,
        List<ManagedFile> managedFiles
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InstalledPackState {
        managedFiles = managedFiles == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(managedFiles));
    }

    public record ManagedFile(
            String path,
            Hashes hashes,
            long size
    ) {
    }
}
