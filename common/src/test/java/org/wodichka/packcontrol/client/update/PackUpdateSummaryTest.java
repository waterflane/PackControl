package org.wodichka.packcontrol.client.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.FileHashing;
import org.wodichka.packcontrol.updateformat.InstalledPackState;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackUpdateSummaryTest {
    @TempDir
    Path instance;

    @Test
    void summarizesOperationsNetworkSizeAndLocalChanges() throws Exception {
        Files.createDirectories(instance.resolve("mods"));
        Files.writeString(instance.resolve("mods/edited.jar"), "local edit");
        Files.writeString(instance.resolve("mods/unchanged.jar"), "unchanged");
        PackControlManifest.Hashes expectedEdited = hashes("original");
        PackControlManifest.Hashes expectedUnchanged =
                FileHashing.inspect(instance.resolve("mods/unchanged.jar")).hashes();
        InstalledPackState state = new InstalledPackState(
                1,
                "fixture",
                "1.0.0",
                "release-1",
                "a".repeat(64),
                List.of(
                        new InstalledPackState.ManagedFile("mods/edited.jar", expectedEdited, 8),
                        new InstalledPackState.ManagedFile(
                                "mods/unchanged.jar",
                                expectedUnchanged,
                                Files.size(instance.resolve("mods/unchanged.jar"))
                        )
                )
        );
        PackUpdatePlan plan = new PackUpdatePlan(
                List.of(
                        operation(PackUpdatePlan.OperationType.ADD, "mods/new.jar",
                                PackUpdatePlan.ContentKind.DOWNLOAD, 100),
                        operation(PackUpdatePlan.OperationType.REPLACE, "mods/edited.jar",
                                PackUpdatePlan.ContentKind.DOWNLOAD, 200),
                        operation(PackUpdatePlan.OperationType.REPLACE, "config/a.toml",
                                PackUpdatePlan.ContentKind.OVERRIDE, 10),
                        operation(PackUpdatePlan.OperationType.ADD, "kubejs/a.js",
                                PackUpdatePlan.ContentKind.OVERRIDE, 20),
                        operation(PackUpdatePlan.OperationType.REMOVE, "mods/old.jar", null, 50),
                        operation(PackUpdatePlan.OperationType.KEEP, "mods/unchanged.jar",
                                PackUpdatePlan.ContentKind.DOWNLOAD, 90)
                ),
                List.of(),
                List.of()
        );
        PackControlManifest manifest = new PackControlManifest(
                1,
                null,
                null,
                List.of(),
                new PackControlManifest.OverridesArchive(
                        "overrides.zip",
                        List.of(),
                        hashes("archive"),
                        500L,
                        List.of()
                ),
                List.of()
        );

        PackUpdateSummary summary =
                PackUpdateSummary.create(manifest, plan, Optional.of(state), instance);

        assertEquals(List.of("kubejs/a.js", "mods/new.jar"), summary.added());
        assertEquals(List.of("config/a.toml", "mods/edited.jar"), summary.updated());
        assertEquals(List.of("mods/old.jar"), summary.removed());
        assertEquals(List.of("mods/unchanged.jar"), summary.kept());
        assertEquals(List.of("mods/edited.jar"), summary.locallyModified());
        assertEquals(800, summary.downloadSize());
    }

    private static PackUpdatePlan.Operation operation(
            PackUpdatePlan.OperationType type,
            String path,
            PackUpdatePlan.ContentKind contentKind,
            long size
    ) {
        return new PackUpdatePlan.Operation(
                type,
                path,
                contentKind,
                List.of("https://example.test/" + path),
                hashes(path),
                size,
                true
        );
    }

    private static PackControlManifest.Hashes hashes(String seed) {
        int value = seed.hashCode();
        String hex = "%08x".formatted(value);
        return new PackControlManifest.Hashes(
                (hex.repeat(5)).substring(0, 40),
                (hex.repeat(8)).substring(0, 64),
                (hex.repeat(16)).substring(0, 128)
        );
    }
}
