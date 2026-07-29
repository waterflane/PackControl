package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trusted-state candidate embedded by the Publisher into an imported mrpack.
 *
 * <p>The descriptor itself and packcontrol-pack.json are bootstrap metadata,
 * not managed pack content.</p>
 */
public record PackBootstrap(
        int schemaVersion,
        String packId,
        String packVersion,
        String releaseId,
        String manifestSha256,
        List<ManagedFile> managedFiles
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PackBootstrap {
        managedFiles = managedFiles == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(managedFiles));
    }

    public InstalledPackState toInstalledState() {
        return new InstalledPackState(
                InstalledPackState.CURRENT_SCHEMA_VERSION,
                packId,
                packVersion,
                releaseId,
                manifestSha256,
                managedFiles
        );
    }
}
