package org.wodichka.packcontrol.packwiz;

import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PackwizGenerator {
    private PackwizGenerator() {
    }

    public static PackwizGenerationResult generate() {
        Path root = PackControlConfig.gameDirectory();
        if (root == null) {
            return PackwizGenerationResult.failed("Game directory is not available");
        }

        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        PackFileSelectionService.PackFileScanResult scan = PackFileSelectionService.scan();

        try {
            Files.createDirectories(root);
            List<HashedFile> hashedFiles = new ArrayList<>();
            for (PackFileSelectionService.PackFileEntry entry : scan.includedFiles()) {
                hashedFiles.add(new HashedFile(entry.relativePath(), sha256(entry.absolutePath())));
            }

            String indexToml = buildIndexToml(hashedFiles, PackControlConfig.user().preserveLocalChanges);
            Path indexPath = safeOutput(root, config.packwizIndexPath);
            Files.writeString(indexPath, indexToml);
            String indexHash = sha256(indexPath);

            String packToml = buildPackToml(config, indexPath.getFileName().toString(), indexHash);
            Files.writeString(safeOutput(root, config.packwizManifestPath), packToml);
            Files.writeString(root.resolve(".gitattributes"), gitattributes());

            config.lastGeneratedAt = Instant.now().toString();
            config.lastGeneratedFileCount = hashedFiles.size();
            config.lastSkippedFileCount = scan.skippedCount();
            config.lastGenerationStatus = "Generated " + hashedFiles.size() + " files";
            PackControlConfig.savePack();
            return new PackwizGenerationResult(true, config.lastGenerationStatus, hashedFiles.size(), scan.skippedCount(), indexHash);
        } catch (IOException | IllegalArgumentException exception) {
            config.lastGenerationStatus = "Generation failed: " + exception.getMessage();
            PackControlConfig.savePack();
            PackControl.LOGGER.error("Packwiz generation failed", exception);
            return PackwizGenerationResult.failed(config.lastGenerationStatus);
        }
    }

    private static Path safeOutput(Path root, String relative) {
        String cleaned = relative.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..")) {
            throw new IllegalArgumentException("Unsafe Packwiz output path: " + relative);
        }

        Path resolved = root.resolve(cleaned).toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Packwiz output path escapes the pack root: " + relative);
        }
        return resolved;
    }

    private static String buildIndexToml(List<HashedFile> files, boolean preserveLocalChanges) {
        StringBuilder builder = new StringBuilder();
        builder.append("hash-format = \"sha256\"\n\n");
        for (HashedFile file : files) {
            builder.append("[[files]]\n");
            builder.append("file = \"").append(toml(file.relativePath())).append("\"\n");
            builder.append("hash = \"").append(file.hash()).append("\"\n");
            if (preserveLocalChanges && shouldPreserve(file.relativePath())) {
                builder.append("preserve = true\n");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private static String buildPackToml(PackControlConfig.PackControlPackConfig config, String indexFile, String indexHash) {
        StringBuilder builder = new StringBuilder();
        builder.append("name = \"").append(toml(config.packName)).append("\"\n");
        builder.append("author = \"").append(toml(config.packAuthor)).append("\"\n");
        builder.append("version = \"").append(toml(config.packVersion)).append("\"\n");
        builder.append("pack-format = \"packwiz:1.1.0\"\n");
        builder.append("description = \"").append(toml(config.packDescription)).append("\"\n\n");
        builder.append("[index]\n");
        builder.append("file = \"").append(toml(indexFile)).append("\"\n");
        builder.append("hash-format = \"sha256\"\n");
        builder.append("hash = \"").append(indexHash).append("\"\n\n");
        builder.append("[versions]\n");
        builder.append("minecraft = \"").append(toml(config.minecraftVersion)).append("\"\n");
        if (!config.modLoader.isBlank() && !config.modLoaderVersion.isBlank()) {
            builder.append(config.modLoader.toLowerCase()).append(" = \"").append(toml(config.modLoaderVersion)).append("\"\n");
        }
        return builder.toString();
    }

    private static String gitattributes() {
        return "# Generated by PackControl for Packwiz-compatible hashes\n"
                + "* text=auto\n"
                + "*.toml text eol=lf\n"
                + "pack.toml text eol=lf\n"
                + "index.toml text eol=lf\n"
                + "*.jar binary\n"
                + "*.zip binary\n"
                + "*.png binary\n"
                + "*.ogg binary\n";
    }

    private static boolean shouldPreserve(String relativePath) {
        return relativePath.startsWith("config/") || relativePath.startsWith("defaultconfigs/") || relativePath.startsWith("kubejs/");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private record HashedFile(String relativePath, String hash) {
    }

    public record PackwizGenerationResult(boolean success, String message, int fileCount, int skippedCount, String indexHash) {
        public static PackwizGenerationResult failed(String message) {
            return new PackwizGenerationResult(false, message, 0, 0, "");
        }
    }
}