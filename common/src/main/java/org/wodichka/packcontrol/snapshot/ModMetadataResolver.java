package org.wodichka.packcontrol.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

public final class ModMetadataResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    private ModMetadataResolver() {
    }

    public static PackSnapshotManifest.ModEntry resolve(Path modFile) throws IOException {
        PackSnapshotManifest.ModEntry entry = new PackSnapshotManifest.ModEntry();
        entry.filename = modFile.getFileName().toString();
        entry.name = readableName(entry.filename);
        entry.size = Files.size(modFile);
        entry.sha256 = hash(modFile, "SHA-256");
        entry.sha1 = hash(modFile, "SHA-1");
        entry.sha512 = hash(modFile, "SHA-512");
        entry.required = true;

        String manual = manualUrl(entry.filename);
        if (!manual.isBlank()) {
            entry.downloadUrl = manual;
            entry.source = sourceFromUrl(manual);
            return entry;
        }

        tryResolveModrinth(entry);
        if (entry.downloadUrl == null || entry.downloadUrl.isBlank()) {
            entry.source = "custom";
            entry.downloadUrl = "";
        }
        return entry;
    }

    private static void tryResolveModrinth(PackSnapshotManifest.ModEntry entry) {
        if (entry.sha512 == null || entry.sha512.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create("https://api.modrinth.com/v2/version_file/" + entry.sha512 + "?algorithm=sha512");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "waterflane/PackControl/0.1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().isBlank()) {
                return;
            }
            JsonObject version = JsonParser.parseString(response.body()).getAsJsonObject();
            entry.name = string(version, "name", entry.name);
            JsonArray files = version.has("files") && version.get("files").isJsonArray() ? version.getAsJsonArray("files") : new JsonArray();
            JsonObject selected = null;
            for (JsonElement element : files) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                if (selected == null || booleanValue(file, "primary", false)) {
                    selected = file;
                }
                if (entry.filename.equals(string(file, "filename", ""))) {
                    selected = file;
                    break;
                }
            }
            if (selected != null) {
                entry.filename = string(selected, "filename", entry.filename);
                entry.downloadUrl = string(selected, "url", "");
                entry.source = entry.downloadUrl.isBlank() ? "modrinth" : "modrinth";
            }
        } catch (IOException exception) {
            PackControl.LOGGER.debug("Modrinth lookup failed for {}", entry.filename, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            PackControl.LOGGER.debug("Modrinth lookup interrupted for {}", entry.filename, exception);
        } catch (RuntimeException exception) {
            PackControl.LOGGER.debug("Modrinth lookup returned unexpected data for {}", entry.filename, exception);
        }
    }

    private static String manualUrl(String filename) {
        for (String value : PackControlConfig.pack().manualModUrls) {
            String cleaned = value == null ? "" : value.trim();
            int equals = cleaned.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = cleaned.substring(0, equals).trim();
            String url = cleaned.substring(equals + 1).trim();
            if (filename.equalsIgnoreCase(key) && isHttpUrl(url)) {
                return url;
            }
        }
        return "";
    }

    public static String sourceFromUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains("modrinth.com") || lower.contains("cdn.modrinth.com")) {
            return "modrinth";
        }
        if (lower.contains("curseforge.com") || lower.contains("forgecdn.net")) {
            return "curseforge";
        }
        if (lower.contains("github.com") || lower.contains("githubusercontent.com")) {
            return "github";
        }
        return "custom";
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    public static String hash(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is not available", exception);
        }
    }

    private static String readableName(String filename) {
        String name = filename.endsWith(".jar") ? filename.substring(0, filename.length() - 4) : filename;
        return name.replace('-', ' ').replace('_', ' ').trim();
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }
}
