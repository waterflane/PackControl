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
import java.util.stream.Stream;

public final class PackSnapshotService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ARCHIVE_NAME = "snapshot-files.zip";

    private PackSnapshotService() {
    }

    public static SnapshotSaveResult saveSnapshot() {
        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            return SnapshotSaveResult.failed("Game directory is not available");
        }

        String snapshotName = snapshotName();
        Path snapshotDir = snapshotsDirectory(root).resolve(snapshotName);
        PackSnapshotManifest manifest = new PackSnapshotManifest();
        manifest.name = snapshotName;
        manifest.createdAt = Instant.now().toEpochMilli();
        manifest.minecraftVersion = PackControlConfig.pack().minecraftVersion;
        manifest.loader = PackControlConfig.pack().modLoader;
        manifest.loaderVersion = PackControlConfig.pack().modLoaderVersion;
        manifest.filesArchive = ARCHIVE_NAME;

        List<PackSnapshotManifest.FileEntry> archiveEntries = new ArrayList<>();
        int unresolved = 0;
        PackFileSelectionService.PackFileScanResult scan = PackFileSelectionService.scan();
        try {
            for (PackFileSelectionService.PackFileEntry entry : scan.includedFiles()) {
                if (!SnapshotArchiveService.safeRelative(entry.relativePath())) {
                    continue;
                }
                if (isMod(entry.relativePath())) {
                    PackSnapshotManifest.ModEntry mod = ModMetadataResolver.resolve(entry.absolutePath());
                    manifest.mods.add(mod);
                    if (mod.required && (mod.downloadUrl == null || mod.downloadUrl.isBlank())) {
                        unresolved++;
                    }
                } else {
                    PackSnapshotManifest.FileEntry file = fileEntry(entry);
                    archiveEntries.add(file);
                    addByType(manifest, file);
                }
            }
            manifest.mods.sort(Comparator.comparing(mod -> mod.filename, String.CASE_INSENSITIVE_ORDER));
            archiveEntries.sort(Comparator.comparing(file -> file.path, String.CASE_INSENSITIVE_ORDER));
            Files.createDirectories(snapshotDir);
            SnapshotArchiveService.writeArchive(root, snapshotDir.resolve(ARCHIVE_NAME), archiveEntries);
            writeSnapshot(snapshotDir.resolve("snapshot.json"), manifest);
            updateStatus(snapshotName, snapshotDir, unresolved, manifest.mods.size(), archiveEntries.size(), "Saved snapshot " + snapshotName);
            return new SnapshotSaveResult(true, "Saved snapshot " + snapshotName + ": " + manifest.mods.size() + " mods, " + archiveEntries.size() + " archived files, " + unresolved + " unresolved mods", snapshotName, snapshotDir, manifest.mods.size(), archiveEntries.size(), unresolved);
        } catch (IOException | RuntimeException exception) {
            PackControl.LOGGER.error("Failed to save PackControl snapshot", exception);
            PackControlConfig.pack().lastSnapshotStatus = "Snapshot failed: " + exception.getMessage();
            PackControlConfig.savePack();
            return SnapshotSaveResult.failed("Snapshot failed: " + exception.getMessage());
        }
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
        Path manifestPath = snapshotDir.resolve("snapshot.json");
        if (Files.notExists(manifestPath)) {
            return LoadedSnapshot.failed("Snapshot file is missing: " + manifestPath);
        }
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            PackSnapshotManifest manifest = GSON.fromJson(reader, PackSnapshotManifest.class);
            if (manifest == null) {
                return LoadedSnapshot.failed("Snapshot is empty");
            }
            PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
            pack.activeSnapshotName = manifest.name;
            pack.activeSnapshotPath = snapshotDir.toString();
            pack.unresolvedSnapshotMods = unresolvedMods(manifest);
            pack.lastSnapshotStatus = "Loaded snapshot " + manifest.name;
            PackControlConfig.savePack();
            return new LoadedSnapshot(true, "Loaded snapshot " + manifest.name, manifest, snapshotDir);
        } catch (IOException | RuntimeException exception) {
            return LoadedSnapshot.failed("Snapshot load failed: " + exception.getMessage());
        }
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

    private static String snapshotName() {
        String base = PackControlConfig.pack().packVersion;
        if (base == null || base.isBlank()) {
            base = "release-1";
        }
        String cleaned = base.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "release-1" : cleaned;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
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
