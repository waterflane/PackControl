package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.BuildMetadata;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverridesArchive;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure semantic validator. It performs no filesystem or network access.
 */
public final class ManifestValidator {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SHA512 = Pattern.compile("^[0-9a-fA-F]{128}$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
    );
    private static final Set<String> OVERRIDE_ROOTS = Set.of("config", "defaultconfigs", "kubejs");

    private final ManifestLimits limits;

    public ManifestValidator() {
        this(ManifestLimits.defaults());
    }

    public ManifestValidator(ManifestLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public ManifestValidationResult validate(PackControlManifest manifest) {
        Validation validation = new Validation(limits);
        validation.validate(manifest);
        return new ManifestValidationResult(validation.errors);
    }

    public void validateOrThrow(PackControlManifest manifest) {
        validate(manifest).throwIfInvalid();
    }

    private static final class Validation {
        private final ManifestLimits limits;
        private final List<ManifestValidationError> errors = new ArrayList<>();
        private final Map<String, RegisteredPath> paths = new HashMap<>();
        private long totalDownloadSize;

        private Validation(ManifestLimits limits) {
            this.limits = limits;
        }

        private void validate(PackControlManifest manifest) {
            if (manifest == null) {
                error(ManifestErrorCode.MANIFEST_REQUIRED, "", "Manifest is required");
                return;
            }

            if (manifest.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
                error(
                        ManifestErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                        "/schemaVersion",
                        "schemaVersion must be " + SUPPORTED_SCHEMA_VERSION
                );
            }

            validateMetadata(manifest.metadata());
            validateSemver(
                    manifest.minimumPackControlVersion(),
                    "/minimumPackControlVersion",
                    "minimumPackControlVersion"
            );
            validateFiles(manifest.files());
            validateOverrides(manifest.overrides());
            validateRemovedFiles(manifest.removedFiles());

            if (totalDownloadSize > limits.maxTotalDownloadSize()) {
                error(
                        ManifestErrorCode.TOTAL_SIZE_LIMIT_EXCEEDED,
                        "",
                        "Total declared download size " + totalDownloadSize
                                + " exceeds limit " + limits.maxTotalDownloadSize()
                );
            }
        }

        private void validateMetadata(BuildMetadata metadata) {
            if (metadata == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, "/metadata", "Build metadata is required");
                return;
            }
            required(metadata.packId(), "/metadata/packId", "packId");
            required(metadata.name(), "/metadata/name", "name");
            validateSemver(metadata.version(), "/metadata/version", "version");
            required(metadata.releaseId(), "/metadata/releaseId", "releaseId");
            required(metadata.minecraftVersion(), "/metadata/minecraftVersion", "minecraftVersion");
            required(metadata.loader(), "/metadata/loader", "loader");
            required(metadata.loaderVersion(), "/metadata/loaderVersion", "loaderVersion");
        }

        private void validateFiles(List<FileEntry> files) {
            if (files == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, "/files", "files is required");
                return;
            }
            if (files.size() > limits.maxFiles()) {
                error(
                        ManifestErrorCode.TOO_MANY_FILES,
                        "/files",
                        "File count " + files.size() + " exceeds limit " + limits.maxFiles()
                );
            }

            for (int index = 0; index < files.size(); index++) {
                FileEntry file = files.get(index);
                String pointer = "/files/" + index;
                if (file == null) {
                    error(ManifestErrorCode.REQUIRED_VALUE, pointer, "File entry is required");
                    continue;
                }

                if (validatePath(file.path(), pointer + "/path")) {
                    registerPath(file.path(), pointer + "/path", PathKind.FILE);
                }
                validateDownloads(
                        file.downloads(),
                        pointer + "/downloads",
                        Boolean.TRUE.equals(file.required())
                );
                validateHashes(file.hashes(), pointer + "/hashes");
                validateSize(file.size(), limits.maxFileSize(), pointer + "/size", false);
                addDownloadSize(file.size());

                if (file.required() == null) {
                    error(ManifestErrorCode.REQUIRED_VALUE, pointer + "/required", "required is required");
                }
                validateEnvironment(file.environment(), pointer + "/environment");
            }
        }

        private void validateOverrides(OverridesArchive overrides) {
            if (overrides == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, "/overrides", "overrides archive is required");
                return;
            }

            if (validatePath(overrides.fileName(), "/overrides/fileName")) {
                if (overrides.fileName().contains("/") || !overrides.fileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    error(
                            ManifestErrorCode.INVALID_OVERRIDE_ARCHIVE,
                            "/overrides/fileName",
                            "Override archive must be a root-level .zip file name"
                    );
                }
            }
            validateDownloads(overrides.downloads(), "/overrides/downloads", true);
            validateHashes(overrides.hashes(), "/overrides/hashes");
            validateSize(
                    overrides.size(),
                    limits.maxOverrideArchiveSize(),
                    "/overrides/size",
                    false
            );
            addDownloadSize(overrides.size());

            List<OverrideEntry> entries = overrides.entries();
            if (entries == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, "/overrides/entries", "Override entries are required");
                return;
            }
            if (entries.size() > limits.maxOverrideEntries()) {
                error(
                        ManifestErrorCode.TOO_MANY_OVERRIDE_ENTRIES,
                        "/overrides/entries",
                        "Override entry count " + entries.size()
                                + " exceeds limit " + limits.maxOverrideEntries()
                );
            }

            for (int index = 0; index < entries.size(); index++) {
                OverrideEntry entry = entries.get(index);
                String pointer = "/overrides/entries/" + index;
                if (entry == null) {
                    error(ManifestErrorCode.REQUIRED_VALUE, pointer, "Override entry is required");
                    continue;
                }
                if (validatePath(entry.path(), pointer + "/path")) {
                    validateOverrideRoot(entry.path(), pointer + "/path");
                    registerPath(entry.path(), pointer + "/path", PathKind.OVERRIDE);
                }
                validateHashes(entry.hashes(), pointer + "/hashes");
                validateSize(
                        entry.size(),
                        limits.maxOverrideEntrySize(),
                        pointer + "/size",
                        true
                );
            }
        }

        private void validateRemovedFiles(List<String> removedFiles) {
            if (removedFiles == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, "/removedFiles", "removedFiles is required");
                return;
            }
            if (removedFiles.size() > limits.maxRemovedFiles()) {
                error(
                        ManifestErrorCode.TOO_MANY_REMOVED_FILES,
                        "/removedFiles",
                        "Removed file count " + removedFiles.size()
                                + " exceeds limit " + limits.maxRemovedFiles()
                );
            }
            for (int index = 0; index < removedFiles.size(); index++) {
                String pointer = "/removedFiles/" + index;
                String path = removedFiles.get(index);
                if (validatePath(path, pointer)) {
                    registerPath(path, pointer, PathKind.REMOVED);
                }
            }
        }

        private void validateEnvironment(Environment environment, String pointer) {
            if (environment == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, pointer, "environment is required");
                return;
            }
            if (environment.client() == null) {
                error(ManifestErrorCode.INVALID_ENVIRONMENT, pointer + "/client", "client environment is required");
            }
            if (environment.server() == null) {
                error(ManifestErrorCode.INVALID_ENVIRONMENT, pointer + "/server", "server environment is required");
            }
            if (environment.client() == EnvironmentRequirement.UNSUPPORTED
                    && environment.server() == EnvironmentRequirement.UNSUPPORTED) {
                error(
                        ManifestErrorCode.INVALID_ENVIRONMENT,
                        pointer,
                        "A file cannot be unsupported on both client and server"
                );
            }
        }

        private void validateDownloads(List<String> downloads, String pointer, boolean required) {
            if (downloads == null || downloads.isEmpty()) {
                if (required) {
                    error(ManifestErrorCode.REQUIRED_VALUE, pointer, "At least one download URL is required");
                }
                return;
            }
            if (downloads.size() > limits.maxDownloadsPerArtifact()) {
                error(
                        ManifestErrorCode.TOO_MANY_DOWNLOADS,
                        pointer,
                        "Download URL count " + downloads.size()
                                + " exceeds limit " + limits.maxDownloadsPerArtifact()
                );
            }

            Set<String> seen = new HashSet<>();
            for (int index = 0; index < downloads.size(); index++) {
                String value = downloads.get(index);
                String itemPointer = pointer + "/" + index;
                if (value == null || value.isBlank()) {
                    error(ManifestErrorCode.INVALID_DOWNLOAD_URL, itemPointer, "Download URL is blank");
                    continue;
                }
                if (!seen.add(value)) {
                    error(ManifestErrorCode.DUPLICATE_DOWNLOAD, itemPointer, "Duplicate download URL");
                }
                try {
                    URI uri = new URI(value);
                    if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                            || uri.getUserInfo() != null || uri.getFragment() != null) {
                        error(
                                ManifestErrorCode.INVALID_DOWNLOAD_URL,
                                itemPointer,
                                "Download URL must be an absolute host URL without credentials or fragment"
                        );
                    } else if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        error(
                                ManifestErrorCode.INSECURE_DOWNLOAD_URL,
                                itemPointer,
                                "Only HTTPS download URLs are allowed"
                        );
                    }
                } catch (URISyntaxException exception) {
                    error(ManifestErrorCode.INVALID_DOWNLOAD_URL, itemPointer, "Invalid download URL");
                }
            }
        }

        private void validateHashes(Hashes hashes, String pointer) {
            if (hashes == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, pointer, "hashes is required");
                return;
            }
            validateHash(hashes.sha1(), SHA1, "SHA-1", pointer + "/sha1");
            validateHash(hashes.sha256(), SHA256, "SHA-256", pointer + "/sha256");
            validateHash(hashes.sha512(), SHA512, "SHA-512", pointer + "/sha512");
        }

        private void validateHash(String value, Pattern pattern, String name, String pointer) {
            if (value == null || value.isBlank()) {
                error(ManifestErrorCode.MISSING_HASH, pointer, name + " is required");
            } else if (!pattern.matcher(value).matches()) {
                error(ManifestErrorCode.INVALID_HASH, pointer, name + " must be a hexadecimal digest");
            }
        }

        private void validateSize(Long size, long maximum, String pointer, boolean allowZero) {
            if (size == null) {
                error(ManifestErrorCode.REQUIRED_VALUE, pointer, "size is required");
                return;
            }
            if (size < 0 || (!allowZero && size == 0)) {
                error(
                        ManifestErrorCode.INVALID_SIZE,
                        pointer,
                        allowZero ? "size must not be negative" : "size must be positive"
                );
            } else if (size > maximum) {
                error(
                        ManifestErrorCode.SIZE_LIMIT_EXCEEDED,
                        pointer,
                        "Declared size " + size + " exceeds limit " + maximum
                );
            }
        }

        private void addDownloadSize(Long size) {
            if (size == null || size <= 0) {
                return;
            }
            if (Long.MAX_VALUE - totalDownloadSize < size) {
                totalDownloadSize = Long.MAX_VALUE;
            } else {
                totalDownloadSize += size;
            }
        }

        private boolean validatePath(String value, String pointer) {
            if (value == null || value.isBlank()) {
                error(ManifestErrorCode.INVALID_PATH, pointer, "Path is required");
                return false;
            }
            if (value.startsWith("/") || value.startsWith("\\")) {
                error(ManifestErrorCode.ABSOLUTE_PATH, pointer, "Absolute paths are forbidden");
                return false;
            }
            if (DRIVE_PREFIX.matcher(value).matches()) {
                error(ManifestErrorCode.DRIVE_PREFIXED_PATH, pointer, "Drive-prefixed paths are forbidden");
                return false;
            }

            String separatorNormalized = value.replace('\\', '/');
            for (String segment : separatorNormalized.split("/", -1)) {
                if ("..".equals(segment)) {
                    error(ManifestErrorCode.PATH_TRAVERSAL, pointer, "Path traversal through '..' is forbidden");
                    return false;
                }
            }

            if (value.indexOf('\\') >= 0) {
                error(ManifestErrorCode.INVALID_PATH, pointer, "Backslashes are forbidden; use '/'");
                return false;
            }
            if (!value.equals(value.trim()) || value.indexOf('\0') >= 0) {
                error(ManifestErrorCode.INVALID_PATH, pointer, "Path contains surrounding whitespace or NUL");
                return false;
            }

            for (String segment : value.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment)
                        || segment.endsWith(".") || segment.endsWith(" ")
                        || segment.indexOf(':') >= 0 || containsControlCharacter(segment)) {
                    error(ManifestErrorCode.INVALID_PATH, pointer, "Path is not in canonical portable form");
                    return false;
                }
            }
            return true;
        }

        private void validateOverrideRoot(String path, String pointer) {
            String root = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            if (!OVERRIDE_ROOTS.contains(root.toLowerCase(Locale.ROOT))) {
                error(
                        ManifestErrorCode.INVALID_OVERRIDE_ROOT,
                        pointer,
                        "Override path must be under config, defaultconfigs, or kubejs"
                );
            }
        }

        private void registerPath(String value, String pointer, PathKind kind) {
            String canonical = value.toLowerCase(Locale.ROOT);
            RegisteredPath existing = paths.get(canonical);
            if (existing != null) {
                ManifestErrorCode code = existing.kind == kind
                        ? ManifestErrorCode.DUPLICATE_PATH
                        : ManifestErrorCode.CONFLICTING_PATH;
                error(code, pointer, "Path conflicts with " + existing.pointer);
                return;
            }

            for (Map.Entry<String, RegisteredPath> entry : paths.entrySet()) {
                String other = entry.getKey();
                if (canonical.startsWith(other + "/") || other.startsWith(canonical + "/")) {
                    error(
                            ManifestErrorCode.CONFLICTING_PATH,
                            pointer,
                            "Path has a file/directory conflict with " + entry.getValue().pointer
                    );
                    return;
                }
            }
            paths.put(canonical, new RegisteredPath(pointer, kind));
        }

        private void validateSemver(String value, String pointer, String name) {
            if (value == null || value.isBlank()) {
                error(ManifestErrorCode.REQUIRED_VALUE, pointer, name + " is required");
            } else if (!SEMVER.matcher(value).matches()) {
                error(ManifestErrorCode.INVALID_VERSION, pointer, name + " must be SemVer 2.0.0");
            }
        }

        private void required(String value, String pointer, String name) {
            if (value == null || value.isBlank()) {
                error(ManifestErrorCode.REQUIRED_VALUE, pointer, name + " is required");
            }
        }

        private void error(ManifestErrorCode code, String pointer, String message) {
            errors.add(new ManifestValidationError(code, pointer, message));
        }

        private static boolean containsControlCharacter(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }
    }

    private enum PathKind {
        FILE,
        OVERRIDE,
        REMOVED
    }

    private record RegisteredPath(String pointer, PathKind kind) {
    }
}
