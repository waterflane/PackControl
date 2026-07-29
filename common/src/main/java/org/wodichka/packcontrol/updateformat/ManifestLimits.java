package org.wodichka.packcontrol.updateformat;

/**
 * Resource limits applied before any update artifacts are downloaded or
 * extracted.
 */
public record ManifestLimits(
        int maxFiles,
        int maxDownloadsPerArtifact,
        int maxOverrideEntries,
        int maxRemovedFiles,
        long maxFileSize,
        long maxOverrideArchiveSize,
        long maxOverrideEntrySize,
        long maxTotalDownloadSize
) {
    public ManifestLimits {
        requirePositive(maxFiles, "maxFiles");
        requirePositive(maxDownloadsPerArtifact, "maxDownloadsPerArtifact");
        requirePositive(maxOverrideEntries, "maxOverrideEntries");
        requirePositive(maxRemovedFiles, "maxRemovedFiles");
        requirePositive(maxFileSize, "maxFileSize");
        requirePositive(maxOverrideArchiveSize, "maxOverrideArchiveSize");
        requirePositive(maxOverrideEntrySize, "maxOverrideEntrySize");
        requirePositive(maxTotalDownloadSize, "maxTotalDownloadSize");
    }

    public static ManifestLimits defaults() {
        return new ManifestLimits(
                4_096,
                8,
                16_384,
                4_096,
                2L * 1024 * 1024 * 1024,
                1024L * 1024 * 1024,
                256L * 1024 * 1024,
                16L * 1024 * 1024 * 1024
        );
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
