package org.wodichka.packcontrol.snapshot;

import org.wodichka.packcontrol.PackControl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SnapshotArchiveService {
    private SnapshotArchiveService() {
    }

    public static void writeArchive(Path root, Path archivePath, List<PackSnapshotManifest.FileEntry> files) throws IOException {
        Files.createDirectories(archivePath.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
            for (PackSnapshotManifest.FileEntry file : files) {
                if (!safeRelative(file.path)) {
                    continue;
                }
                Path source = root.resolve(file.path).normalize();
                if (!source.startsWith(root.normalize()) || Files.notExists(source) || !Files.isRegularFile(source)) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(file.archiveEntry == null || file.archiveEntry.isBlank() ? file.path : file.archiveEntry);
                output.putNextEntry(entry);
                Files.copy(source, output);
                output.closeEntry();
            }
        }
    }

    public static void extractArchive(Path archivePath, Path root, List<PackSnapshotManifest.FileEntry> files, Path backupDirectory) throws IOException {
        if (Files.notExists(archivePath)) {
            return;
        }
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archivePath))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !safeRelative(entry.getName())) {
                    input.closeEntry();
                    continue;
                }
                PackSnapshotManifest.FileEntry manifestEntry = findByArchiveEntry(files, entry.getName());
                if (manifestEntry == null || !safeRelative(manifestEntry.path)) {
                    input.closeEntry();
                    continue;
                }
                Path target = root.resolve(manifestEntry.path).normalize();
                if (!target.startsWith(root.normalize())) {
                    input.closeEntry();
                    continue;
                }
                backupIfExists(root, target, backupDirectory);
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    input.transferTo(output);
                }
                input.closeEntry();
            }
        }
    }

    public static void backupIfExists(Path root, Path target, Path backupDirectory) throws IOException {
        if (Files.notExists(target) || !Files.isRegularFile(target)) {
            return;
        }
        Path relative = root.normalize().relativize(target.normalize());
        Path backup = backupDirectory.resolve(relative.toString()).normalize();
        if (!backup.startsWith(backupDirectory.normalize())) {
            PackControl.LOGGER.warn("Skipping unsafe backup path {}", backup);
            return;
        }
        Files.createDirectories(backup.getParent());
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static PackSnapshotManifest.FileEntry findByArchiveEntry(List<PackSnapshotManifest.FileEntry> files, String archiveEntry) {
        for (PackSnapshotManifest.FileEntry file : files) {
            String expected = file.archiveEntry == null || file.archiveEntry.isBlank() ? file.path : file.archiveEntry;
            if (archiveEntry.equals(expected)) {
                return file;
            }
        }
        return null;
    }

    public static boolean safeRelative(String value) {
        return value != null
                && !value.isBlank()
                && !value.startsWith("/")
                && !value.startsWith("\\")
                && !value.contains("..")
                && !value.equals(".packcontrol")
                && !value.startsWith(".packcontrol/");
    }
}
