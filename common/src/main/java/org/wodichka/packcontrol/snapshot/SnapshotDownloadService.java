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
import java.util.function.Consumer;

public final class SnapshotDownloadService {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private SnapshotDownloadService() {
    }

    public static SnapshotInstallPlan previewLatest() {
        return previewSelected();
    }

    public static SnapshotInstallPlan previewSelected() {
        PackSnapshotService.LoadedSnapshot loaded = PackSnapshotService.selectedSnapshot();
        if (!loaded.success()) {
            return SnapshotInstallPlan.failed(loaded.message());
        }
        return preview(loaded.manifest(), loaded.snapshotDirectory());
    }

    public static SnapshotInstallResult installLatest() {
        return installSelected(progress -> { });
    }

    public static SnapshotInstallResult installSelected(Consumer<SnapshotProgress> progress) {
        Consumer<SnapshotProgress> reporter = progress == null ? ignored -> { } : progress;
        reporter.accept(SnapshotProgress.step("Preparing", 0, 1, "Opening selected snapshot"));
        PackSnapshotService.LoadedSnapshot loaded = PackSnapshotService.selectedSnapshot();
        if (!loaded.success()) {
            SnapshotInstallResult result = SnapshotInstallResult.failed(loaded.message());
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }

        reporter.accept(SnapshotProgress.step("Previewing", 1, 4, loaded.manifest().name));
        SnapshotInstallPlan plan = preview(loaded.manifest(), loaded.snapshotDirectory());
        if (!plan.success()) {
            SnapshotInstallResult result = SnapshotInstallResult.failed(plan.message());
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }
Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            SnapshotInstallResult result = SnapshotInstallResult.failed("Game directory is not available");
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }

        Path backupDir = root.resolve(".packcontrol/backups").resolve(String.valueOf(Instant.now().toEpochMilli()));
        Path downloadsDir = root.resolve(".packcontrol/downloads");
        List<PackSnapshotManifest.FileEntry> archivedFiles = archivedFiles(loaded.manifest());
        int total = Math.max(loaded.manifest().mods.size() + archivedFiles.size() + 3, 1);
        int step = 2;
        try {
            reporter.accept(SnapshotProgress.step("Backing up", step++, total, "Preparing safe install"));
            Files.createDirectories(backupDir);
            Files.createDirectories(downloadsDir);
            int installedMods = 0;
            int skippedMods = 0;

            for (PackSnapshotManifest.ModEntry mod : loaded.manifest().mods) {
                reporter.accept(SnapshotProgress.step("Downloading mods", step++, total, mod.filename));
                if (mod.downloadUrl == null || mod.downloadUrl.isBlank()) {
                    skippedMods++;
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
                reporter.accept(SnapshotProgress.step("Verifying", step, total, mod.filename));
                String downloadedHash = ModMetadataResolver.hash(downloaded, "SHA-256");
                if (!mod.sha256.equalsIgnoreCase(downloadedHash)) {
                    String message = "Hash mismatch for " + mod.filename;
                    updateDownloadStatus(message, backupDir.toString());
                    SnapshotInstallResult result = SnapshotInstallResult.failed(message);
                    reporter.accept(SnapshotProgress.done(result.message(), false));
                    return result;
                }
                Files.createDirectories(target.getParent());
                Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                installedMods++;
            }

            reporter.accept(SnapshotProgress.step("Restoring files", total - 1, total, archivedFiles.size() + " archived files"));
            SnapshotArchiveService.extractArchive(loaded.snapshotDirectory().resolve(loaded.manifest().filesArchive), root, archivedFiles, backupDir);
            String message = "Installed snapshot " + loaded.manifest().name + ": " + installedMods + " mods, " + archivedFiles.size() + " archived files" + (skippedMods > 0 ? ", skipped " + skippedMods + " mods without downloadUrl" : "");
            updateDownloadStatus(message, backupDir.toString());
            SnapshotInstallResult result = new SnapshotInstallResult(true, message, backupDir, installedMods, archivedFiles.size());
            reporter.accept(SnapshotProgress.done(result.message(), true));
            return result;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            PackControl.LOGGER.error("Snapshot install failed", exception);
            String message = "Install failed: " + exception.getMessage();
            updateDownloadStatus(message, backupDir.toString());
            SnapshotInstallResult result = SnapshotInstallResult.failed(message);
            reporter.accept(SnapshotProgress.done(result.message(), false));
            return result;
        }
    }

    public static SnapshotInstallPlan preview(PackSnapshotManifest manifest, Path snapshotDirectory) {
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
        String message = "Preview " + manifest.name + ": +" + added + " mods, ~" + updated + " mods, " + archivedFiles.size() + " archived files" + (unresolved > 0 ? ", " + unresolved + " mods will be skipped without URL" : "");
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