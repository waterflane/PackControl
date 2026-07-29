package org.wodichka.packcontrol.publisher;

import org.wodichka.packcontrol.updateformat.FileHashing;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PublisherScanner {
    private static final List<String> OVERRIDE_ROOTS = List.of("config", "defaultconfigs", "kubejs");

    public ScanResult scan(Path instance) throws IOException, PublisherException {
        Path root = instance.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new PublisherException("Instance directory does not exist: " + root);
        }

        List<ScannedFile> mods = scanMods(root);
        List<ScannedFile> overrides = new ArrayList<>();
        for (String overrideRoot : OVERRIDE_ROOTS) {
            Path directory = root.resolve(overrideRoot);
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                scanTree(root, directory, overrides, false);
            }
        }

        mods.sort(Comparator.comparing(ScannedFile::path));
        overrides.sort(Comparator.comparing(ScannedFile::path));
        rejectDuplicatePaths(mods, overrides);
        return new ScanResult(mods, overrides);
    }

    private static List<ScannedFile> scanMods(Path root) throws IOException, PublisherException {
        List<ScannedFile> result = new ArrayList<>();
        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        scanTree(root, mods, result, true);
        return result;
    }

    private static void scanTree(
            Path instance,
            Path directory,
            List<ScannedFile> target,
            boolean jarsOnly
    ) throws IOException, PublisherException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new PublisherException("Symbolic links are not allowed in publisher input: " + path);
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = portable(instance.relativize(path));
                boolean jar = relative.toLowerCase(Locale.ROOT).endsWith(".jar");
                if (jarsOnly && !jar) {
                    continue;
                }
                if (!jarsOnly && jar) {
                    throw new PublisherException("JAR files are not allowed in overrides: " + relative);
                }
                FileHashing.DigestedContent digest = FileHashing.inspect(path);
                target.add(new ScannedFile(relative, path, digest.size(), digest.hashes()));
            }
        }
    }

    private static void rejectDuplicatePaths(List<ScannedFile> mods, List<ScannedFile> overrides)
            throws PublisherException {
        Set<String> paths = new HashSet<>();
        for (ScannedFile file : concat(mods, overrides)) {
            if (!paths.add(file.path().toLowerCase(Locale.ROOT))) {
                throw new PublisherException("Conflicting case-insensitive input path: " + file.path());
            }
        }
    }

    private static List<ScannedFile> concat(List<ScannedFile> first, List<ScannedFile> second) {
        List<ScannedFile> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    public record ScanResult(List<ScannedFile> mods, List<ScannedFile> overrides) {
        public ScanResult {
            mods = List.copyOf(mods);
            overrides = List.copyOf(overrides);
        }
    }

    public record ScannedFile(String path, Path source, long size, Hashes hashes) {
    }
}
