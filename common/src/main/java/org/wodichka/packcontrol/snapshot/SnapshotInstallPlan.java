package org.wodichka.packcontrol.snapshot;

import java.nio.file.Path;
import java.util.List;

public record SnapshotInstallPlan(
        boolean success,
        String message,
        String snapshotName,
        int addedMods,
        int updatedMods,
        int unchangedMods,
        int archivedFiles,
        int unresolvedMods,
        List<String> warnings,
        List<String> affectedPaths,
        Path snapshotDirectory
) {
    public static SnapshotInstallPlan failed(String message) {
        return new SnapshotInstallPlan(false, message, "", 0, 0, 0, 0, 0, List.of(message), List.of(), Path.of("."));
    }

    public int changedFiles() {
        return addedMods + updatedMods + archivedFiles;
    }
}
