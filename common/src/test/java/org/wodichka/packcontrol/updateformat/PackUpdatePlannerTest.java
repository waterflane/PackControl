package org.wodichka.packcontrol.updateformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.IssueCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PackUpdatePlannerTest {
    @TempDir
    Path instanceRoot;

    @Test
    void requiredFileWithoutSourceBlocksPlan() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry missingSource = new FileEntry(
                "mods/required.jar",
                List.of(),
                ManifestTestFixtures.validHashes(),
                10L,
                true,
                new Environment(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.REQUIRED)
        );
        PackControlManifest manifest = ManifestTestFixtures.withFiles(base, List.of(missingSource));

        PackUpdatePlan plan = new PackUpdatePlanner().plan(manifest, Optional.empty(), instanceRoot);

        assertTrue(plan.isBlocked());
        assertTrue(plan.errors().stream().anyMatch(issue ->
                issue.code() == IssueCode.MISSING_REQUIRED_SOURCE
                        && issue.pointer().equals("/files/0/downloads")
        ));
    }

    @Test
    void optionalFileWithoutSourceDoesNotBlockPlan() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry missingSource = new FileEntry(
                "mods/optional.jar",
                List.of(),
                ManifestTestFixtures.validHashes(),
                10L,
                false,
                new Environment(EnvironmentRequirement.OPTIONAL, EnvironmentRequirement.OPTIONAL)
        );
        PackControlManifest manifest = ManifestTestFixtures.withFiles(base, List.of(missingSource));

        PackUpdatePlan plan = new PackUpdatePlanner().plan(manifest, Optional.empty(), instanceRoot);

        assertTrue(!plan.isBlocked());
        assertTrue(plan.warnings().stream().anyMatch(issue ->
                issue.code() == IssueCode.OPTIONAL_SOURCE_UNAVAILABLE
        ));
    }
}
