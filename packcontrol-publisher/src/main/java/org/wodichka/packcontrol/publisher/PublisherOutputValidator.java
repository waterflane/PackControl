package org.wodichka.packcontrol.publisher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wodichka.packcontrol.updateformat.FileHashing;
import org.wodichka.packcontrol.updateformat.ManifestJson;
import org.wodichka.packcontrol.updateformat.ManifestValidationError;
import org.wodichka.packcontrol.updateformat.ManifestValidator;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PublisherOutputValidator {
    public List<String> validate(Path output) {
        List<String> errors = new ArrayList<>();
        try {
            Path manifestPath = output.resolve(PackControlPublisher.MANIFEST_FILE);
            if (!Files.isRegularFile(manifestPath)) {
                return List.of("Missing " + PackControlPublisher.MANIFEST_FILE);
            }
            PackControlManifest manifest;
            try (Reader reader = Files.newBufferedReader(manifestPath)) {
                manifest = ManifestJson.fromJson(reader);
            }
            for (ManifestValidationError error : new ManifestValidator().validate(manifest).errors()) {
                errors.add("manifest " + error.pointer() + ": " + error.message());
            }
            if (!errors.isEmpty()) {
                return List.copyOf(errors);
            }

            validateOverrides(output.resolve(manifest.overrides().fileName()), manifest, errors);
            Path mrpack = findMrpack(output, errors);
            if (mrpack != null) {
                validateMrpack(mrpack, manifest, errors);
            }
            validateChecksums(output, mrpack, errors);
        } catch (Exception exception) {
            errors.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return List.copyOf(errors);
    }

    private static void validateOverrides(
            Path archive,
            PackControlManifest manifest,
            List<String> errors
    ) throws IOException {
        if (!Files.isRegularFile(archive)) {
            errors.add("Missing " + manifest.overrides().fileName());
            return;
        }
        FileHashing.DigestedContent archiveDigest = FileHashing.inspect(archive);
        compare("overrides.zip", manifest.overrides().size(), manifest.overrides().hashes(), archiveDigest, errors);
        Map<String, OverrideEntry> expected = new HashMap<>();
        for (OverrideEntry entry : manifest.overrides().entries()) {
            expected.put(entry.path(), entry);
        }
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    errors.add("Unexpected directory entry in overrides.zip: " + entry.getName());
                    continue;
                }
                OverrideEntry expectedEntry = expected.get(entry.getName());
                if (expectedEntry == null) {
                    errors.add("Unexpected overrides.zip entry: " + entry.getName());
                    continue;
                }
                if (!seen.add(entry.getName())) {
                    errors.add("Duplicate overrides.zip entry: " + entry.getName());
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    FileHashing.DigestedContent digest = FileHashing.inspect(input);
                    compare(entry.getName(), expectedEntry.size(), expectedEntry.hashes(), digest, errors);
                }
            }
        }
        for (String path : expected.keySet()) {
            if (!seen.contains(path)) {
                errors.add("Missing overrides.zip entry: " + path);
            }
        }
    }

    private static void validateMrpack(
            Path mrpack,
            PackControlManifest manifest,
            List<String> errors
    ) throws IOException {
        Map<String, OverrideEntry> expectedOverrideEntries = new HashMap<>();
        for (OverrideEntry entry : manifest.overrides().entries()) {
            expectedOverrideEntries.put("overrides/" + entry.path(), entry);
        }
        try (ZipFile zip = new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null) {
                errors.add(".mrpack is missing modrinth.index.json");
                return;
            }
            try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(indexEntry), StandardCharsets.UTF_8)) {
                JsonObject index = JsonParser.parseReader(reader).getAsJsonObject();
                require(index, "formatVersion", 1, errors);
                require(index, "game", "minecraft", errors);
                JsonObject dependencies = index.getAsJsonObject("dependencies");
                if (dependencies == null
                        || !manifest.metadata().minecraftVersion().equals(string(dependencies, "minecraft"))
                        || !manifest.metadata().loaderVersion().equals(string(dependencies, "neoforge"))) {
                    errors.add(".mrpack dependencies do not match manifest metadata");
                }
                validateMrpackFiles(index.getAsJsonArray("files"), manifest, errors);
            }

            Set<String> seenOverrides = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().equals("modrinth.index.json")) {
                    continue;
                }
                OverrideEntry expected = expectedOverrideEntries.get(entry.getName());
                if (expected == null) {
                    errors.add("Unexpected .mrpack entry: " + entry.getName());
                    continue;
                }
                if (!seenOverrides.add(entry.getName())) {
                    errors.add("Duplicate .mrpack entry: " + entry.getName());
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    compare(entry.getName(), expected.size(), expected.hashes(), FileHashing.inspect(input), errors);
                }
            }
            for (String expected : expectedOverrideEntries.keySet()) {
                if (!seenOverrides.contains(expected)) {
                    errors.add("Missing .mrpack override: " + expected);
                }
            }
        }
    }

    private static void validateMrpackFiles(
            JsonArray files,
            PackControlManifest manifest,
            List<String> errors
    ) {
        if (files == null || files.size() != manifest.files().size()) {
            errors.add(".mrpack file count does not match manifest");
            return;
        }
        Map<String, PackControlManifest.FileEntry> expected = new HashMap<>();
        manifest.files().forEach(file -> expected.put(file.path(), file));
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String path = string(file, "path");
            PackControlManifest.FileEntry manifestFile = expected.remove(path);
            if (manifestFile == null) {
                errors.add("Unexpected .mrpack file: " + path);
                continue;
            }
            JsonObject hashes = file.getAsJsonObject("hashes");
            if (hashes == null
                    || !manifestFile.hashes().sha1().equals(string(hashes, "sha1"))
                    || !manifestFile.hashes().sha512().equals(string(hashes, "sha512"))
                    || file.get("fileSize").getAsLong() != manifestFile.size()) {
                errors.add(".mrpack metadata mismatch for " + path);
            }
        }
        expected.keySet().forEach(path -> errors.add("Missing .mrpack file: " + path));
    }

    private static void validateChecksums(Path output, Path mrpack, List<String> errors) throws IOException {
        Path file = output.resolve(PackControlPublisher.CHECKSUMS_FILE);
        if (!Files.isRegularFile(file)) {
            errors.add("Missing " + PackControlPublisher.CHECKSUMS_FILE);
            return;
        }
        Map<String, String> listed = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("  ", 2);
            if (parts.length != 2 || listed.put(parts[1], parts[0]) != null) {
                errors.add("Invalid checksums.txt line: " + line);
            }
        }
        List<Path> artifacts = new ArrayList<>();
        artifacts.add(output.resolve(PackControlPublisher.MANIFEST_FILE));
        artifacts.add(output.resolve(PackControlPublisher.OVERRIDES_FILE));
        if (mrpack != null) {
            artifacts.add(mrpack);
        }
        for (Path artifact : artifacts) {
            String expected = listed.remove(artifact.getFileName().toString());
            String actual = FileHashing.inspect(artifact).hashes().sha256();
            if (!actual.equalsIgnoreCase(expected == null ? "" : expected)) {
                errors.add("Checksum mismatch for " + artifact.getFileName());
            }
        }
        listed.keySet().forEach(name -> errors.add("Unexpected checksum entry: " + name));
    }

    private static Path findMrpack(Path output, List<String> errors) throws IOException {
        try (var paths = Files.list(output)) {
            List<Path> mrpacks = paths
                    .filter(path -> path.getFileName().toString().endsWith(".mrpack"))
                    .toList();
            if (mrpacks.size() != 1) {
                errors.add("Expected exactly one .mrpack, found " + mrpacks.size());
                return null;
            }
            return mrpacks.getFirst();
        }
    }

    private static void compare(
            String name,
            long expectedSize,
            Hashes expectedHashes,
            FileHashing.DigestedContent actual,
            List<String> errors
    ) {
        if (expectedSize != actual.size()) {
            errors.add("Size mismatch for " + name);
        }
        if (!expectedHashes.sha1().equalsIgnoreCase(actual.hashes().sha1())
                || !expectedHashes.sha256().equalsIgnoreCase(actual.hashes().sha256())
                || !expectedHashes.sha512().equalsIgnoreCase(actual.hashes().sha512())) {
            errors.add("Hash mismatch for " + name);
        }
    }

    private static void require(JsonObject object, String property, int value, List<String> errors) {
        if (!object.has(property) || object.get(property).getAsInt() != value) {
            errors.add(".mrpack " + property + " must be " + value);
        }
    }

    private static void require(JsonObject object, String property, String value, List<String> errors) {
        if (!value.equals(string(object, property))) {
            errors.add(".mrpack " + property + " must be " + value);
        }
    }

    private static String string(JsonObject object, String property) {
        JsonElement value = object.get(property);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
