package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class PackBootstrapValidator {
    public static final int MAX_MANAGED_FILES = 20_480;
    public static final long MAX_MANAGED_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    public static final long MAX_TOTAL_SIZE = 20L * 1024 * 1024 * 1024;

    private static final Pattern SHA1 = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SHA512 = Pattern.compile("^[0-9a-fA-F]{128}$");
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    public List<Issue> validate(PackBootstrap bootstrap) {
        List<Issue> issues = new ArrayList<>();
        if (bootstrap == null) {
            return List.of(new Issue("", "Bootstrap is required"));
        }
        if (bootstrap.schemaVersion() != PackBootstrap.CURRENT_SCHEMA_VERSION) {
            issues.add(new Issue(
                    "/schemaVersion",
                    "schemaVersion must be " + PackBootstrap.CURRENT_SCHEMA_VERSION
            ));
        }
        required(bootstrap.packId(), "/packId", issues);
        if (SemanticVersion.tryParse(bootstrap.packVersion()).isEmpty()) {
            issues.add(new Issue("/packVersion", "packVersion must be valid SemVer"));
        }
        required(bootstrap.releaseId(), "/releaseId", issues);
        hash(bootstrap.manifestSha256(), SHA256, "/manifestSha256", "SHA-256", issues);

        List<ManagedFile> files = bootstrap.managedFiles();
        if (files == null) {
            issues.add(new Issue("/managedFiles", "managedFiles is required"));
            return List.copyOf(issues);
        }
        if (files.size() > MAX_MANAGED_FILES) {
            issues.add(new Issue(
                    "/managedFiles",
                    "Managed file count exceeds " + MAX_MANAGED_FILES
            ));
        }

        Map<String, RegisteredPath> registered = new HashMap<>();
        long totalSize = 0;
        for (int index = 0; index < files.size(); index++) {
            ManagedFile file = files.get(index);
            String pointer = "/managedFiles/" + index;
            if (file == null) {
                issues.add(new Issue(pointer, "Managed file is required"));
                continue;
            }
            if (safePath(file.path(), pointer + "/path", issues)) {
                register(file.path(), pointer + "/path", registered, issues);
            }
            validateHashes(file.hashes(), pointer + "/hashes", issues);
            if (file.size() < 0 || file.size() > MAX_MANAGED_FILE_SIZE) {
                issues.add(new Issue(
                        pointer + "/size",
                        "size must be between 0 and " + MAX_MANAGED_FILE_SIZE
                ));
            } else if (Long.MAX_VALUE - totalSize < file.size()) {
                totalSize = Long.MAX_VALUE;
            } else {
                totalSize += file.size();
            }
        }
        validateConflicts(registered, issues);
        if (totalSize > MAX_TOTAL_SIZE) {
            issues.add(new Issue(
                    "/managedFiles",
                    "Total managed size exceeds " + MAX_TOTAL_SIZE
            ));
        }
        return List.copyOf(issues);
    }

    private static boolean safePath(String path, String pointer, List<Issue> issues) {
        if (path == null || path.isBlank()) {
            issues.add(new Issue(pointer, "Path is required"));
            return false;
        }
        if (path.startsWith("/") || path.startsWith("\\") || DRIVE_PREFIX.matcher(path).matches()) {
            issues.add(new Issue(pointer, "Absolute and drive-prefixed paths are forbidden"));
            return false;
        }
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            issues.add(new Issue(pointer, "Only portable '/' paths are allowed"));
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.indexOf(':') >= 0) {
                issues.add(new Issue(pointer, "Unsafe path segment"));
                return false;
            }
        }
        if (segments[0].equalsIgnoreCase(".packcontrol")) {
            issues.add(new Issue(pointer, "Managed files may not target .packcontrol"));
            return false;
        }
        return true;
    }

    private static void register(
            String path,
            String pointer,
            Map<String, RegisteredPath> registered,
            List<Issue> issues
    ) {
        String canonical = path.toLowerCase(Locale.ROOT);
        RegisteredPath duplicate = registered.putIfAbsent(canonical, new RegisteredPath(path, pointer));
        if (duplicate != null) {
            issues.add(new Issue(pointer, "Duplicate managed path: " + path));
        }
    }

    private static void validateConflicts(
            Map<String, RegisteredPath> registered,
            List<Issue> issues
    ) {
        for (Map.Entry<String, RegisteredPath> entry : registered.entrySet()) {
            String path = entry.getKey();
            int slash = path.indexOf('/');
            while (slash >= 0) {
                if (registered.containsKey(path.substring(0, slash))) {
                    issues.add(new Issue(
                            entry.getValue().pointer(),
                            "Conflicting parent/child managed path: " + entry.getValue().path()
                    ));
                    break;
                }
                slash = path.indexOf('/', slash + 1);
            }
        }
    }

    private static void validateHashes(Hashes hashes, String pointer, List<Issue> issues) {
        if (hashes == null) {
            issues.add(new Issue(pointer, "hashes is required"));
            return;
        }
        hash(hashes.sha1(), SHA1, pointer + "/sha1", "SHA-1", issues);
        hash(hashes.sha256(), SHA256, pointer + "/sha256", "SHA-256", issues);
        hash(hashes.sha512(), SHA512, pointer + "/sha512", "SHA-512", issues);
    }

    private static void hash(
            String value,
            Pattern pattern,
            String pointer,
            String name,
            List<Issue> issues
    ) {
        if (value == null || !pattern.matcher(value).matches()) {
            issues.add(new Issue(pointer, name + " must be a hexadecimal digest"));
        }
    }

    private static void required(String value, String pointer, List<Issue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(new Issue(pointer, "Value is required"));
        }
    }

    public record Issue(String pointer, String message) {
    }

    private record RegisteredPath(String path, String pointer) {
    }
}
