package org.wodichka.packcontrol.client.update;

import org.wodichka.packcontrol.updateformat.FileHashing;
import org.wodichka.packcontrol.updateformat.InstalledPackState;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.Operation;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.OperationType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record PackUpdateSummary(
        List<String> added,
        List<String> updated,
        List<String> removed,
        List<String> kept,
        List<String> locallyModified,
        long downloadSize
) {
    public PackUpdateSummary {
        added = sorted(added);
        updated = sorted(updated);
        removed = sorted(removed);
        kept = sorted(kept);
        locallyModified = sorted(locallyModified);
    }

    public static PackUpdateSummary create(
            PackControlManifest manifest,
            PackUpdatePlan plan,
            Optional<InstalledPackState> installedState,
            Path instanceRoot
    ) {
        List<String> added = paths(plan, OperationType.ADD);
        List<String> updated = paths(plan, OperationType.REPLACE);
        List<String> removed = paths(plan, OperationType.REMOVE);
        List<String> kept = paths(plan, OperationType.KEEP);
        List<String> locallyModified = detectLocalChanges(installedState, instanceRoot);

        long downloadSize = 0;
        boolean downloadsOverrides = false;
        for (Operation operation : plan.operations()) {
            if (operation.type() != OperationType.ADD && operation.type() != OperationType.REPLACE) {
                continue;
            }
            if (operation.contentKind() == PackUpdatePlan.ContentKind.DOWNLOAD) {
                downloadSize = saturatedAdd(downloadSize, operation.size());
            } else if (operation.contentKind() == PackUpdatePlan.ContentKind.OVERRIDE) {
                downloadsOverrides = true;
            }
        }
        if (downloadsOverrides && manifest.overrides() != null && manifest.overrides().size() != null) {
            downloadSize = saturatedAdd(downloadSize, manifest.overrides().size());
        }
        return new PackUpdateSummary(added, updated, removed, kept, locallyModified, downloadSize);
    }

    public int changedCount() {
        return added.size() + updated.size() + removed.size();
    }

    private static List<String> paths(PackUpdatePlan plan, OperationType type) {
        return plan.operations().stream()
                .filter(operation -> operation.type() == type)
                .map(Operation::path)
                .toList();
    }

    private static List<String> detectLocalChanges(
            Optional<InstalledPackState> installedState,
            Path instanceRoot
    ) {
        if (installedState.isEmpty() || installedState.get().managedFiles() == null) {
            return List.of();
        }
        Path root = instanceRoot.toAbsolutePath().normalize();
        List<String> result = new ArrayList<>();
        for (InstalledPackState.ManagedFile managed : installedState.get().managedFiles()) {
            Path target = root.resolve(managed.path().replace('/', java.io.File.separatorChar)).normalize();
            try {
                if (!target.startsWith(root)
                        || !Files.isRegularFile(target)
                        || Files.isSymbolicLink(target)
                        || !managed.hashes().sha256().equalsIgnoreCase(FileHashing.inspect(target).hashes().sha256())) {
                    result.add(managed.path());
                }
            } catch (IOException | RuntimeException exception) {
                result.add(managed.path());
            }
        }
        return result;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) {
            return left;
        }
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
