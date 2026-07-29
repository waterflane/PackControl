package org.wodichka.packcontrol.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;
import org.wodichka.packcontrol.packwiz.PackFileSelectionService;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class PackSnapshotService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ARCHIVE_NAME = "snapshot-files.zip";

    private PackSnapshotService() {
    }

    public static SnapshotSaveResult saveSnapshot() {
        return saveSnapshot(SnapshotSaveOptions.defaults(), progress -> { });
    }

    public static SnapshotSaveResult saveSnapshot(SnapshotSaveOptions options, Consumer<SnapshotProgress> progress) {
        Consumer<SnapshotProgress> reporter = progress == null ? ignored -> { } : progress;
        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            SnapshotSaveResult result = SnapshotSaveResult.failed("Game directory is not available");
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }

        try {
            reporter.accept(SnapshotProgress.step("Preparing", 0, 6, "Reading pack metadata"));
            String snapshotName = snapshotName(options);
            Path snapshotDir = snapshotsDirectory(root).resolve(snapshotName);
            PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();

            PackSnapshotManifest manifest = new PackSnapshotManifest();
            manifest.name = snapshotName;
            manifest.createdAt = Instant.now().toEpochMilli();
            manifest.version = clean(options.version(), pack.packVersion);
            manifest.commitMessage = clean(options.commitMessage(), "");
            manifest.author = clean(options.author(), pack.packAuthor);
            manifest.minecraftVersion = pack.minecraftVersion;
            manifest.loader = pack.modLoader;
            manifest.loaderVersion = pack.modLoaderVersion;
            manifest.filesArchive = ARCHIVE_NAME;

            reporter.accept(SnapshotProgress.step("Scanning", 1, 6, "Collecting selected files"));
            PackFileSelectionService.PackFileScanResult scan = PackFileSelectionService.scan();
            List<PackFileSelectionService.PackFileEntry> entries = scan.includedFiles();
            List<PackSnapshotManifest.FileEntry> archiveEntries = new ArrayList<>();
            int unresolved = 0;
            int total = Math.max(entries.size(), 1);
            int index = 0;

            for (PackFileSelectionService.PackFileEntry entry : entries) {
                index++;
                if (!SnapshotArchiveService.safeRelative(entry.relativePath())) {
                    continue;
                }
                if (isMod(entry.relativePath())) {
                    reporter.accept(SnapshotProgress.step("Resolving mods", index, total, entry.relativePath()));
                    PackSnapshotManifest.ModEntry mod = ModMetadataResolver.resolve(entry.absolutePath());
                    manifest.mods.add(mod);
                    if (mod.required && (mod.downloadUrl == null || mod.downloadUrl.isBlank())) {
                        unresolved++;
                    }
                } else {
                    reporter.accept(SnapshotProgress.step("Hashing files", index, total, entry.relativePath()));
                    PackSnapshotManifest.FileEntry file = fileEntry(entry);
                    archiveEntries.add(file);
                    addByType(manifest, file);
                }
            }

            reporter.accept(SnapshotProgress.step("Sorting", 4, 6, "Preparing manifest"));
            manifest.mods.sort(Comparator.comparing(mod -> mod.filename, String.CASE_INSENSITIVE_ORDER));
            archiveEntries.sort(Comparator.comparing(file -> file.path, String.CASE_INSENSITIVE_ORDER));

            reporter.accept(SnapshotProgress.step("Archiving", 5, 6, archiveEntries.size() + " files"));
            Files.createDirectories(snapshotDir);
            SnapshotArchiveService.writeArchive(root, snapshotDir.resolve(ARCHIVE_NAME), archiveEntries);

            reporter.accept(SnapshotProgress.step("Writing", 6, 6, "snapshot.json"));
            writeSnapshot(snapshotDir.resolve("snapshot.json"), manifest);
            updateStatus(snapshotName, snapshotDir, unresolved, manifest.mods.size(), archiveEntries.size(), "Saved snapshot " + snapshotName);
            PackControlConfig.pack().selectedSnapshotPath = snapshotDir.toString();
            PackControlConfig.savePack();

            SnapshotSaveResult result = new SnapshotSaveResult(true, "Saved snapshot " + snapshotName + ": " + manifest.mods.size() + " mods, " + archiveEntries.size() + " archived files, " + unresolved + " unresolved mods", snapshotName, snapshotDir, manifest.mods.size(), archiveEntries.size(), unresolved);
            reporter.accept(SnapshotProgress.done(result.message(), true));
            return result;
        } catch (IOException | RuntimeException exception) {
            PackControl.LOGGER.error("Failed to save PackControl snapshot", exception);
            PackControlConfig.pack().lastSnapshotStatus = "Snapshot failed: " + exception.getMessage();
            PackControlConfig.savePack();
            SnapshotSaveResult result = SnapshotSaveResult.failed("Snapshot failed: " + exception.getMessage());
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }
    }

    public static List<SnapshotSummary> snapshots() {
        Path root = PackControlConfig.gameDirectory();
        if (root == null || Files.notExists(snapshotsDirectory(root))) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(snapshotsDirectory(root))) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve("snapshot.json")))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .map(path -> {
                        LoadedSnapshot loaded = loadSnapshotNoStatus(path);
                        String name = loaded.success() && loaded.manifest() != null ? loaded.manifest().name : path.getFileName().toString();
                        String version = loaded.success() && loaded.manifest() != null ? loaded.manifest().version : "";
                        long createdAt = loaded.success() && loaded.manifest() != null ? loaded.manifest().createdAt : lastModified(path);
                        int mods = loaded.success() && loaded.manifest() != null && loaded.manifest().mods != null ? loaded.manifest().mods.size() : 0;
                        int unresolved = loaded.success() && loaded.manifest() != null ? unresolvedMods(loaded.manifest()) : 0;
                        return new SnapshotSummary(name, version, createdAt, path, mods, unresolved);
                    })
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    public static LoadedSnapshot selectedSnapshot() {
        String selected = PackControlConfig.pack().selectedSnapshotPath;
        if (selected != null && !selected.isBlank()) {
            LoadedSnapshot loaded = loadSnapshot(Path.of(selected));
            if (loaded.success()) {
                return loaded;
            }
        }
        return latestSnapshot();
    }

    public static LoadedSnapshot selectSnapshot(Path snapshotDir) {
        LoadedSnapshot loaded = loadSnapshot(snapshotDir);
        if (loaded.success()) {
            PackControlConfig.pack().selectedSnapshotPath = snapshotDir.toString();
            PackControlConfig.savePack();
        }
        return loaded;
    }

    public static LoadedSnapshot latestSnapshot() {
        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            return LoadedSnapshot.failed("Game directory is not available");
        }
        Path snapshotsDir = snapshotsDirectory(root);
        if (Files.notExists(snapshotsDir)) {
            return LoadedSnapshot.failed("No snapshots saved yet");
        }
        try (Stream<Path> stream = Files.list(snapshotsDir)) {
            List<Path> candidates = stream.filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve("snapshot.json")))
                    .sorted((left, right) -> Long.compare(lastModified(right), lastModified(left)))
                    .toList();
            if (candidates.isEmpty()) {
                return LoadedSnapshot.failed("No snapshots saved yet");
            }
            return loadSnapshot(candidates.get(0));
        } catch (IOException exception) {
            return LoadedSnapshot.failed("Snapshot load failed: " + exception.getMessage());
        }
    }

    public static LoadedSnapshot loadSnapshot(Path snapshotDir) {
        LoadedSnapshot loaded = loadSnapshotNoStatus(snapshotDir);
        if (!loaded.success()) {
            return loaded;
        }
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        pack.activeSnapshotName = loaded.manifest().name;
        pack.activeSnapshotPath = loaded.snapshotDirectory().toString();
        pack.selectedSnapshotPath = loaded.snapshotDirectory().toString();
        pack.unresolvedSnapshotMods = unresolvedMods(loaded.manifest());
        pack.lastSnapshotStatus = "Loaded snapshot " + loaded.manifest().name;
        PackControlConfig.savePack();
        return loaded;
    }

    public static List<String> unresolvedModNames(PackSnapshotManifest manifest) {
        List<String> names = new ArrayList<>();
        if (manifest == null || manifest.mods == null) {
            return names;
        }
        for (PackSnapshotManifest.ModEntry mod : manifest.mods) {
            if (mod.required && (mod.downloadUrl == null || mod.downloadUrl.isBlank())) {
                names.add(mod.filename == null || mod.filename.isBlank() ? mod.name : mod.filename);
            }
        }
        return names;
    }

    public static Path snapshotsDirectory(Path root) {
        return root.resolve(".packcontrol/snapshots");
    }

    private static void updateStatus(String snapshotName, Path snapshotDir, int unresolved, int modCount, int fileCount, String message) {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        pack.activeSnapshotName = snapshotName;
        pack.activeSnapshotPath = snapshotDir.toString();
        pack.selectedSnapshotPath = snapshotDir.toString();
        pack.unresolvedSnapshotMods = unresolved;
        pack.lastSnapshotStatus = message + " (" + modCount + " mods, " + fileCount + " files)";
        PackControlConfig.savePack();
    }

    private static int unresolvedMods(PackSnapshotManifest manifest) {
        return unresolvedModNames(manifest).size();
    }

    private static PackSnapshotManifest.FileEntry fileEntry(PackFileSelectionService.PackFileEntry entry) throws IOException {
        PackSnapshotManifest.FileEntry file = new PackSnapshotManifest.FileEntry();
        file.path = entry.relativePath();
        file.archiveEntry = entry.relativePath();
        file.sha256 = ModMetadataResolver.hash(entry.absolutePath(), "SHA-256");
        file.size = entry.size();
        file.type = fileType(entry.relativePath());
        return file;
    }

    private static void addByType(PackSnapshotManifest manifest, PackSnapshotManifest.FileEntry file) {
        if (file.path.startsWith("config/") || file.path.startsWith("defaultconfigs/")) {
            manifest.configs.add(file);
        } else if (file.path.startsWith("kubejs/")) {
            manifest.kubejs.add(file);
        } else {
            manifest.files.add(file);
        }
    }

    private static void writeSnapshot(Path path, PackSnapshotManifest manifest) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(manifest, writer);
        }
    }

    private static boolean isMod(String relativePath) {
        return relativePath.startsWith("mods/") && relativePath.toLowerCase().endsWith(".jar");
    }

    private static String fileType(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash <= 0 ? "file" : relativePath.substring(0, slash);
    }

    private static LoadedSnapshot loadSnapshotNoStatus(Path snapshotDir) {
        Path manifestPath = snapshotDir.resolve("snapshot.json");
        if (Files.notExists(manifestPath)) {
            return LoadedSnapshot.failed("Snapshot file is missing: " + manifestPath);
        }
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            PackSnapshotManifest manifest = GSON.fromJson(reader, PackSnapshotManifest.class);
            return manifest == null ? LoadedSnapshot.failed("Snapshot is empty") : new LoadedSnapshot(true, "Loaded snapshot " + manifest.name, manifest, snapshotDir);
        } catch (IOException | RuntimeException exception) {
            return LoadedSnapshot.failed("Snapshot load failed: " + exception.getMessage());
        }
    }

    private static String snapshotName(SnapshotSaveOptions options) {
        String base = clean(options.name(), PackControlConfig.pack().packVersion);
        if (base == null || base.isBlank()) {
            base = "release-1";
        }
        String cleaned = base.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "release-1" : cleaned;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    public record SnapshotSummary(String name, String version, long createdAt, Path snapshotDirectory, int modCount, int unresolvedMods) {
    }

    public record SnapshotSaveResult(boolean success, String message, String snapshotName, Path snapshotDirectory, int modCount, int archivedFileCount, int unresolvedMods) {
        public static SnapshotSaveResult failed(String message) {
            return new SnapshotSaveResult(false, message, "", Path.of("."), 0, 0, 0);
        }
    }

    public record LoadedSnapshot(boolean success, String message, PackSnapshotManifest manifest, Path snapshotDirectory) {
        public static LoadedSnapshot failed(String message) {
            return new LoadedSnapshot(false, message, null, Path.of("."));
        }
    }
}