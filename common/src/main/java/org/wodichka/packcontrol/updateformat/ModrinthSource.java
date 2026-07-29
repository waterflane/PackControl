package org.wodichka.packcontrol.updateformat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackFileResolution.Issue;
import org.wodichka.packcontrol.updateformat.PackFileResolution.IssueCode;
import org.wodichka.packcontrol.updateformat.PackSourceReference.ModrinthReference;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModrinthSource implements PackFileSource {
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;

    private final URI apiBase;
    private final PackHttpClient http;
    private final MetadataCache<VersionMetadata> cache;
    private final int maxBatchSize;

    public ModrinthSource() {
        this(
                URI.create("https://api.modrinth.com/v2/"),
                new PackHttpClient(),
                Duration.ofMinutes(15),
                DEFAULT_MAX_BATCH_SIZE
        );
    }

    public ModrinthSource(
            URI apiBase,
            PackHttpClient http,
            Duration cacheTtl,
            int maxBatchSize
    ) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.apiBase = ensureTrailingSlash(apiBase);
        this.http = http;
        this.cache = new MetadataCache<>(cacheTtl);
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public String sourceId() {
        return "modrinth";
    }

    @Override
    public boolean supports(PackSourceReference reference) {
        return reference instanceof ModrinthReference;
    }

    @Override
    public Map<String, PackFileResolution> resolve(
            List<PackFileRequest> requests,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        Map<String, VersionMetadata> metadataByHash = new LinkedHashMap<>();
        List<String> missingHashes = new ArrayList<>();
        for (PackFileRequest request : requests) {
            if (!supports(request.source())) {
                continue;
            }
            String hash = request.hashes().sha512().toLowerCase(Locale.ROOT);
            cache.get(hash).ifPresentOrElse(
                    metadata -> metadataByHash.put(hash, metadata),
                    () -> {
                        if (!missingHashes.contains(hash)) {
                            missingHashes.add(hash);
                        }
                    }
            );
        }

        for (int offset = 0; offset < missingHashes.size(); offset += maxBatchSize) {
            int end = Math.min(offset + maxBatchSize, missingHashes.size());
            List<String> batch = missingHashes.subList(offset, end);
            Map<String, VersionMetadata> fetched = fetchBatch(batch, cancellation);
            for (String hash : batch) {
                VersionMetadata metadata = fetched.getOrDefault(hash, VersionMetadata.notFound());
                cache.put(hash, metadata);
                metadataByHash.put(hash, metadata);
            }
        }

        Map<String, PackFileResolution> results = new LinkedHashMap<>();
        for (PackFileRequest request : requests) {
            if (!(request.source() instanceof ModrinthReference reference)) {
                results.put(
                        request.requestId(),
                        unresolved(request, IssueCode.INVALID_REFERENCE, "Request is not a Modrinth reference")
                );
                continue;
            }
            String hash = request.hashes().sha512().toLowerCase(Locale.ROOT);
            VersionMetadata metadata = metadataByHash.getOrDefault(hash, VersionMetadata.notFound());
            results.put(request.requestId(), select(request, reference, metadata));
        }
        return results;
    }

    private Map<String, VersionMetadata> fetchBatch(
            List<String> hashes,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        String requestBody = GSON.toJson(Map.of("hashes", hashes, "algorithm", "sha512"));
        PackHttpClient.TextResponse response = http.postJson(
                apiBase.resolve("version_files"),
                requestBody,
                Map.of("Accept", "application/json", "Content-Type", "application/json"),
                cancellation
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth metadata request returned HTTP " + response.statusCode());
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body());
        } catch (RuntimeException exception) {
            throw new IOException("Modrinth returned invalid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Modrinth version_files response must be an object");
        }

        Map<String, VersionMetadata> result = new LinkedHashMap<>();
        JsonObject root = parsed.getAsJsonObject();
        for (String hash : hashes) {
            JsonElement versionElement = root.get(hash);
            if (versionElement == null || !versionElement.isJsonObject()) {
                continue;
            }
            result.put(hash, parseVersion(hash, versionElement.getAsJsonObject()));
        }
        return result;
    }

    private VersionMetadata parseVersion(String requestedHash, JsonObject version) {
        JsonArray files = version.has("files") && version.get("files").isJsonArray()
                ? version.getAsJsonArray("files")
                : new JsonArray();
        List<FileMetadata> matches = new ArrayList<>();
        for (JsonElement element : files) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            JsonObject hashes = file.has("hashes") && file.get("hashes").isJsonObject()
                    ? file.getAsJsonObject("hashes")
                    : new JsonObject();
            String sha512 = string(hashes, "sha512");
            String sha1 = string(hashes, "sha1");
            String url = string(file, "url");
            String filename = string(file, "filename");
            if (!requestedHash.equalsIgnoreCase(sha512)
                    || sha1 == null || filename == null || url == null) {
                continue;
            }
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    continue;
                }
                matches.add(new FileMetadata(
                        uri,
                        filename,
                        longValue(file, "size", -1),
                        new Hashes(sha1, null, sha512),
                        booleanValue(file, "primary")
                ));
            } catch (IllegalArgumentException ignored) {
                // Invalid upstream URL becomes an unresolved metadata entry.
            }
        }
        return new VersionMetadata(matches);
    }

    private PackFileResolution select(
            PackFileRequest request,
            ModrinthReference reference,
            VersionMetadata metadata
    ) {
        if (metadata.files.isEmpty()) {
            return unresolved(
                    request,
                    IssueCode.NOT_FOUND,
                    "Modrinth has no file for SHA-512 " + request.hashes().sha512()
            );
        }
        String preferred = reference.preferredFileName();
        List<FileMetadata> ordered = metadata.files.stream()
                .sorted(Comparator
                        .comparing((FileMetadata file) ->
                                preferred != null && preferred.equals(file.fileName) ? 0 : 1)
                        .thenComparing(file -> file.primary ? 0 : 1))
                .toList();
        List<PackFileCandidate> candidates = ordered.stream()
                .map(file -> new PackFileCandidate(
                        file.uri,
                        sourceId(),
                        file.fileName,
                        file.size,
                        file.hashes,
                        java.util.Set.of("modrinth.com")
                ))
                .toList();
        return new PackFileResolution(request.requestId(), candidates, List.of());
    }

    private static PackFileResolution unresolved(
            PackFileRequest request,
            IssueCode code,
            String message
    ) {
        return new PackFileResolution(request.requestId(), List.of(), List.of(new Issue(code, message)));
    }

    private static URI ensureTrailingSlash(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : null;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsLong()
                : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    private record VersionMetadata(List<FileMetadata> files) {
        private VersionMetadata {
            files = List.copyOf(files);
        }

        private static VersionMetadata notFound() {
            return new VersionMetadata(List.of());
        }
    }

    private record FileMetadata(
            URI uri,
            String fileName,
            long size,
            Hashes hashes,
            boolean primary
    ) {
    }
}
