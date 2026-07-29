package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.ContentKind;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.Issue;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.IssueCode;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.Operation;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.OperationType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PackUpdatePlanner {
    private final ManifestValidator validator;

    public PackUpdatePlanner() {
        this(new ManifestValidator());
    }

    public PackUpdatePlanner(ManifestValidator validator) {
        this.validator = validator;
    }

    public PackUpdatePlan plan(
            PackControlManifest manifest,
            Optional<InstalledPackState> installedState,
            Path instanceRoot
    ) {
        List<Operation> operations = new ArrayList<>();
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        Path root = instanceRoot.toAbsolutePath().normalize();

        ManifestValidationResult validation = validator.validate(manifest);
        for (ManifestValidationError error : validation.errors()) {
            errors.add(new Issue(
                    IssueCode.INVALID_MANIFEST,
                    error.pointer(),
                    error.code() + ": " + error.message()
            ));
        }
        if (manifest == null) {
            return new PackUpdatePlan(operations, errors, warnings);
        }
        collectMissingRequiredSourceIssues(manifest, errors);
        if (!validation.isValid()) {
            return new PackUpdatePlan(operations, errors, warnings);
        }

        Map<String, ManagedFile> previous = previousFiles(installedState, errors);
        if (installedState.isPresent()
                && installedState.get().packId() != null
                && !installedState.get().packId().equals(manifest.metadata().packId())) {
            errors.add(new Issue(
                    IssueCode.INVALID_INSTALLED_STATE,
                    "/packId",
                    "Installed pack id does not match manifest pack id"
            ));
        }
        if (!errors.isEmpty()) {
            return new PackUpdatePlan(operations, errors, warnings);
        }

        Map<String, DesiredFile> desired = new HashMap<>();
        collectDownloads(manifest, desired, errors, warnings);
        collectOverrides(manifest, desired);
        if (!errors.isEmpty()) {
            return new PackUpdatePlan(operations, errors, warnings);
        }

        Set<String> desiredPaths = new HashSet<>();
        for (DesiredFile file : desired.values()) {
            desiredPaths.add(canonical(file.path));
            ManagedFile old = previous.get(canonical(file.path));
            Path target = resolve(root, file.path);
            try {
                if (!file.sourceAvailable) {
                    if (old != null && Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                        operations.add(new Operation(
                                OperationType.KEEP,
                                file.path,
                                ContentKind.DOWNLOAD,
                                List.of(),
                                FileHashing.hashes(target),
                                Files.size(target),
                                false
                        ));
                    }
                    continue;
                }
                if (old == null && Files.exists(target)) {
                    errors.add(new Issue(
                            IssueCode.UNMANAGED_PATH_CONFLICT,
                            file.pointer,
                            "Target exists but is not managed by installed-state: " + file.path
                    ));
                    continue;
                }

                OperationType type;
                if (Files.notExists(target)) {
                    type = OperationType.ADD;
                } else if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
                    errors.add(new Issue(
                            IssueCode.UNMANAGED_PATH_CONFLICT,
                            file.pointer,
                            "Target is not a regular managed file: " + file.path
                    ));
                    continue;
                } else if (file.hashes.sha256().equalsIgnoreCase(FileHashing.sha256(target))) {
                    type = OperationType.KEEP;
                } else {
                    type = OperationType.REPLACE;
                }
                operations.add(file.operation(type));
            } catch (IOException exception) {
                errors.add(new Issue(
                        IssueCode.UNREADABLE_PATH,
                        file.pointer,
                        "Cannot inspect " + file.path + ": " + exception.getMessage()
                ));
            }
        }

        for (ManagedFile old : previous.values()) {
            String canonical = canonical(old.path());
            if (!desiredPaths.contains(canonical)) {
                operations.add(new Operation(
                        OperationType.REMOVE,
                        old.path(),
                        null,
                        List.of(),
                        old.hashes(),
                        old.size(),
                        true
                ));
            }
        }

        for (int index = 0; manifest.removedFiles() != null && index < manifest.removedFiles().size(); index++) {
            String path = manifest.removedFiles().get(index);
            if (!previous.containsKey(canonical(path))) {
                warnings.add(new Issue(
                        IssueCode.UNMANAGED_REMOVE_IGNORED,
                        "/removedFiles/" + index,
                        "Removal ignored because the path was not managed: " + path
                ));
            }
        }

        return new PackUpdatePlan(operations, errors, warnings);
    }

    private static void collectMissingRequiredSourceIssues(
            PackControlManifest manifest,
            List<Issue> errors
    ) {
        if (manifest.files() == null) {
            return;
        }
        for (int index = 0; index < manifest.files().size(); index++) {
            FileEntry file = manifest.files().get(index);
            if (file != null
                    && Boolean.TRUE.equals(file.required())
                    && (file.downloads() == null || file.downloads().isEmpty())) {
                errors.add(new Issue(
                        IssueCode.MISSING_REQUIRED_SOURCE,
                        "/files/" + index + "/downloads",
                        "Required file has no download source: " + file.path()
                ));
            }
        }
    }

    private static Map<String, ManagedFile> previousFiles(
            Optional<InstalledPackState> installedState,
            List<Issue> errors
    ) {
        Map<String, ManagedFile> previous = new HashMap<>();
        if (installedState.isEmpty()) {
            return previous;
        }
        InstalledPackState state = installedState.get();
        if (state.schemaVersion() != InstalledPackState.CURRENT_SCHEMA_VERSION
                || state.managedFiles() == null) {
            errors.add(new Issue(
                    IssueCode.INVALID_INSTALLED_STATE,
                    "",
                    "Unsupported or incomplete installed-state.json"
            ));
            return previous;
        }
        for (int index = 0; index < state.managedFiles().size(); index++) {
            ManagedFile file = state.managedFiles().get(index);
            if (file == null || file.path() == null || file.hashes() == null) {
                errors.add(new Issue(
                        IssueCode.INVALID_INSTALLED_STATE,
                        "/managedFiles/" + index,
                        "Invalid managed file entry"
                ));
                continue;
            }
            if (!isPortableManagedPath(file.path()) || file.size() < 0) {
                errors.add(new Issue(
                        IssueCode.INVALID_INSTALLED_STATE,
                        "/managedFiles/" + index,
                        "Unsafe managed file entry: " + file.path()
                ));
                continue;
            }
            if (previous.putIfAbsent(canonical(file.path()), file) != null) {
                errors.add(new Issue(
                        IssueCode.INVALID_INSTALLED_STATE,
                        "/managedFiles/" + index,
                        "Duplicate managed path: " + file.path()
                ));
            }
        }
        return previous;
    }

    private static void collectDownloads(
            PackControlManifest manifest,
            Map<String, DesiredFile> desired,
            List<Issue> errors,
            List<Issue> warnings
    ) {
        if (manifest.files() == null) {
            return;
        }
        for (int index = 0; index < manifest.files().size(); index++) {
            FileEntry file = manifest.files().get(index);
            if (file == null) {
                continue;
            }
            String pointer = "/files/" + index;
            boolean sourceAvailable = file.downloads() != null && !file.downloads().isEmpty();
            if (!sourceAvailable && Boolean.TRUE.equals(file.required())) {
                errors.add(new Issue(
                        IssueCode.MISSING_REQUIRED_SOURCE,
                        pointer + "/downloads",
                        "Required file has no download source: " + file.path()
                ));
                continue;
            }
            if (!sourceAvailable) {
                warnings.add(new Issue(
                        IssueCode.OPTIONAL_SOURCE_UNAVAILABLE,
                        pointer + "/downloads",
                        "Optional file has no download source and will be skipped: " + file.path()
                ));
            }
            desired.put(
                    canonical(file.path()),
                    new DesiredFile(
                            file.path(),
                            pointer,
                            ContentKind.DOWNLOAD,
                            file.downloads(),
                            file.hashes(),
                            file.size(),
                            Boolean.TRUE.equals(file.required()),
                            sourceAvailable
                    )
            );
        }
    }

    private static void collectOverrides(
            PackControlManifest manifest,
            Map<String, DesiredFile> desired
    ) {
        if (manifest.overrides() == null || manifest.overrides().entries() == null) {
            return;
        }
        for (int index = 0; index < manifest.overrides().entries().size(); index++) {
            OverrideEntry entry = manifest.overrides().entries().get(index);
            if (entry == null) {
                continue;
            }
            desired.put(
                    canonical(entry.path()),
                    new DesiredFile(
                            entry.path(),
                            "/overrides/entries/" + index,
                            ContentKind.OVERRIDE,
                            manifest.overrides().downloads(),
                            entry.hashes(),
                            entry.size(),
                            true,
                            true
                    )
            );
        }
    }

    private static Path resolve(Path root, String relative) {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar))
                .toAbsolutePath()
                .normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes instance root: " + relative);
        }
        return target;
    }

    private static String canonical(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static boolean isPortableManagedPath(String path) {
        if (path == null || path.isBlank()
                || path.startsWith("/") || path.startsWith("\\")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || segment.indexOf(':') >= 0) {
                return false;
            }
        }
        return true;
    }

    private record DesiredFile(
            String path,
            String pointer,
            ContentKind kind,
            List<String> downloads,
            PackControlManifest.Hashes hashes,
            long size,
            boolean required,
            boolean sourceAvailable
    ) {
        private Operation operation(OperationType type) {
            return new Operation(type, path, kind, downloads, hashes, size, required);
        }
    }
}
