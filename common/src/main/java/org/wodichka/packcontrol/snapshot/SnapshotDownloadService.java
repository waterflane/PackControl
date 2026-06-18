package org.wodichka.packcontrol.snapshot;

import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SnapshotDownloadService {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private SnapshotDownloadService() {
    }

    public static SnapshotInstallPlan previewLatest() {
        PackSnapshotService.LoadedSnapshot loaded = PackSnapshotService.latestSnapshot();
        if (!loaded.success()) {
            return SnapshotInstallPlan.failed(loaded.message());
        }
        return preview(loaded.manifest(), loaded.snapshotDirectory());
    }

    public static SnapshotInstallResult installLatest() {
        PackSnapshotService.LoadedSnapshot loaded = PackSnapshotService.latestSnapshot();
        if (!loaded.success()) {
            return SnapshotInstallResult.failed(loaded.message());
        }
        SnapshotInstallPlan plan = preview(loaded.manifest(), loaded.snapshotDirectory());
        if (!plan.success()) {
            return SnapshotInstallResult.failed(plan.message());
        }
        if (plan.unresolvedMods() > 0) {
            String message = "Download blocked: " + plan.unresolvedMods() + " mods need downloadUrl";
            updateDownloadStatus(message, "");
            return SnapshotInstallResult.failed(message);
        }

        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            return SnapshotInstallResult.failed("Game directory is not available");
        }
        Path backupDir = root.resolve(".packcontrol/backups").resolve(String.valueOf(Instant.now().toEpochMilli()));
        Path downloadsDir = root.resolve(".packcontrol/downloads");
        try {
            Files.createDirectories(backupDir);
            Files.createDirectories(downloadsDir);
            int installedMods = 0;
            for (PackSnapshotManifest.ModEntry mod : loaded.manifest().mods) {
                if (mod.downloadUrl == null || mod.downloadUrl.isBlank()) {
                    continue;
                }
                Path target = root.resolve("mods").resolve(mod.filename).normalize();
                if (!target.startsWith(root.normalize())) {
                    continue;
                }
                String currentHash = Files.exists(target) ? ModMetadataResolver.hash(target, "SHA-256") : "";
                if (mod.sha256.equalsIgnoreCase(currentHash)) {
                    continue;
                }
                SnapshotArchiveService.backupIfExists(root, target, backupDir);
                Path downloaded = downloadMod(downloadsDir, mod);
                String downloadedHash = ModMetadataResolver.hash(downloaded, "SHA-256");
                if (!mod.sha256.equalsIgnoreCase(downloadedHash)) {
                    String message = "Hash mismatch for " + mod.filename;
                    updateDownloadStatus(message, backupDir.toString());
                    return SnapshotInstallResult.failed(message);
                }
                Files.createDirectories(target.getParent());
                Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                installedMods++;
            }
            List<PackSnapshotManifest.FileEntry> archivedFiles = archivedFiles(loaded.manifest());
            SnapshotArchiveService.extractArchive(loaded.snapshotDirectory().resolve(loaded.manifest().filesArchive), root, archivedFiles, backupDir);
            String message = "Installed snapshot " + loaded.manifest().name + ": " + installedMods + " mods, " + archivedFiles.size() + " archived files";
            updateDownloadStatus(message, backupDir.toString());
            return new SnapshotInstallResult(true, message, backupDir, installedMods, archivedFiles.size());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            PackControl.LOGGER.error("Snapshot install failed", exception);
            String message = "Install failed: " + exception.getMessage();
            updateDownloadStatus(message, backupDir.toString());
            return SnapshotInstallResult.failed(message);
        }
    }

    private static SnapshotInstallPlan preview(PackSnapshotManifest manifest, Path snapshotDirectory) {
        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            return SnapshotInstallPlan.failed("Game directory is not available");
        }
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int unresolved = 0;
        List<String> warnings = new ArrayList<>();
        List<String> affected = new ArrayList<>();

        for (PackSnapshotManifest.ModEntry mod : manifest.mods) {
            if (mod.required && (mod.downloadUrl == null || mod.downloadUrl.isBlank())) {
                unresolved++;
                warnings.add("Missing URL: " + mod.filename);
            }
            Path target = root.resolve("mods").resolve(mod.filename).normalize();
            if (!target.startsWith(root.normalize())) {
                warnings.add("Unsafe mod path: " + mod.filename);
                continue;
            }
            try {
                if (Files.notExists(target)) {
                    added++;
                    affected.add("mods/" + mod.filename);
                } else if (!mod.sha256.equalsIgnoreCase(ModMetadataResolver.hash(target, "SHA-256"))) {
                    updated++;
                    affected.add("mods/" + mod.filename);
                } else {
                    unchanged++;
                }
            } catch (IOException exception) {
                warnings.add("Unreadable existing mod: " + mod.filename);
            }
        }

        List<PackSnapshotManifest.FileEntry> archivedFiles = archivedFiles(manifest);
        for (PackSnapshotManifest.FileEntry file : archivedFiles) {
            if (SnapshotArchiveService.safeRelative(file.path)) {
                affected.add(file.path);
            }
        }
        String message = "Preview " + manifest.name + ": +" + added + " mods, ~" + updated + " mods, " + archivedFiles.size() + " archived files, " + unresolved + " unresolved";
        return new SnapshotInstallPlan(true, message, manifest.name, added, updated, unchanged, archivedFiles.size(), unresolved, List.copyOf(warnings), List.copyOf(affected), snapshotDirectory);
    }

    private static Path downloadMod(Path downloadsDir, PackSnapshotManifest.ModEntry mod) throws IOException, InterruptedException {
        URI uri = URI.create(mod.downloadUrl);
        Path target = downloadsDir.resolve(mod.filename + ".download").normalize();
        if (!target.startsWith(downloadsDir.normalize())) {
            throw new IOException("Unsafe download target: " + mod.filename);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "waterflane/PackControl/0.1.0")
                .GET()
                .build();
        HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed for " + mod.filename + ": HTTP " + response.statusCode());
        }
        return target;
    }

    public static List<PackSnapshotManifest.FileEntry> archivedFiles(PackSnapshotManifest manifest) {
        List<PackSnapshotManifest.FileEntry> files = new ArrayList<>();
        if (manifest.configs != null) {
            files.addAll(manifest.configs);
        }
        if (manifest.kubejs != null) {
            files.addAll(manifest.kubejs);
        }
        if (manifest.files != null) {
            files.addAll(manifest.files);
        }
        return files;
    }

    private static void updateDownloadStatus(String message, String backupPath) {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        pack.lastDownloadStatus = message;
        pack.lastBackupPath = backupPath == null ? "" : backupPath;
        PackControlConfig.savePack();
    }

    public record SnapshotInstallResult(boolean success, String message, Path backupDirectory, int installedMods, int restoredFiles) {
        public static SnapshotInstallResult failed(String message) {
            return new SnapshotInstallResult(false, message, Path.of("."), 0, 0);
        }
    }
}
