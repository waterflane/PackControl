package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.BuildMetadata;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverridesArchive;

import java.util.List;

final class ManifestTestFixtures {
    private ManifestTestFixtures() {
    }

    static PackControlManifest validManifest() {
        return new PackControlManifest(
                1,
                new BuildMetadata(
                        "example-pack",
                        "Example Pack",
                        "1.2.3",
                        "release-1.2.3",
                        "1.21.1",
                        "neoforge",
                        "21.1.233"
                ),
                "0.2.0",
                List.of(validFile("mods/example.jar", 1_024)),
                validOverrides(),
                List.of("mods/old-example.jar")
        );
    }

    static FileEntry validFile(String path, long size) {
        return new FileEntry(
                path,
                List.of("https://cdn.modrinth.com/data/example/versions/1/example.jar"),
                validHashes(),
                size,
                true,
                new Environment(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.UNSUPPORTED)
        );
    }

    static OverridesArchive validOverrides() {
        return new OverridesArchive(
                "overrides.zip",
                List.of("https://downloads.example.org/packs/overrides.zip"),
                validHashes(),
                2_048L,
                List.of(new OverrideEntry("config/example.toml", validHashes(), 32L))
        );
    }

    static Hashes validHashes() {
        return new Hashes("a".repeat(40), "b".repeat(64), "c".repeat(128));
    }

    static PackControlManifest withFiles(PackControlManifest manifest, List<FileEntry> files) {
        return new PackControlManifest(
                manifest.schemaVersion(),
                manifest.metadata(),
                manifest.minimumPackControlVersion(),
                files,
                manifest.overrides(),
                manifest.removedFiles()
        );
    }

    static PackControlManifest withOverrides(PackControlManifest manifest, OverridesArchive overrides) {
        return new PackControlManifest(
                manifest.schemaVersion(),
                manifest.metadata(),
                manifest.minimumPackControlVersion(),
                manifest.files(),
                overrides,
                manifest.removedFiles()
        );
    }

    static PackControlManifest withRemovedFiles(PackControlManifest manifest, List<String> removedFiles) {
        return new PackControlManifest(
                manifest.schemaVersion(),
                manifest.metadata(),
                manifest.minimumPackControlVersion(),
                manifest.files(),
                manifest.overrides(),
                removedFiles
        );
    }
}
