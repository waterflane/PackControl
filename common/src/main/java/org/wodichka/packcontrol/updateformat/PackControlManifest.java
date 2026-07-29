package org.wodichka.packcontrol.updateformat;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loader- and UI-independent representation of a PackControl update manifest.
 */
public record PackControlManifest(
        int schemaVersion,
        BuildMetadata metadata,
        String minimumPackControlVersion,
        List<FileEntry> files,
        OverridesArchive overrides,
        List<String> removedFiles
) {
    public PackControlManifest {
        files = immutableOrNull(files);
        removedFiles = immutableOrNull(removedFiles);
    }

    private static <T> List<T> immutableOrNull(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public record BuildMetadata(
            String packId,
            String name,
            String version,
            String releaseId,
            String minecraftVersion,
            String loader,
            String loaderVersion
    ) {
    }

    public record FileEntry(
            String path,
            List<String> downloads,
            Hashes hashes,
            Long size,
            Boolean required,
            Environment environment
    ) {
        public FileEntry {
            downloads = immutableOrNull(downloads);
        }
    }

    public record Hashes(
            String sha1,
            String sha256,
            String sha512
    ) {
    }

    public record Environment(
            EnvironmentRequirement client,
            EnvironmentRequirement server
    ) {
    }

    public enum EnvironmentRequirement {
        @SerializedName("required")
        REQUIRED,
        @SerializedName("optional")
        OPTIONAL,
        @SerializedName("unsupported")
        UNSUPPORTED
    }

    public record OverridesArchive(
            String fileName,
            List<String> downloads,
            Hashes hashes,
            Long size,
            List<OverrideEntry> entries
    ) {
        public OverridesArchive {
            downloads = immutableOrNull(downloads);
            entries = immutableOrNull(entries);
        }
    }

    public record OverrideEntry(
            String path,
            Hashes hashes,
            Long size
    ) {
    }
}
