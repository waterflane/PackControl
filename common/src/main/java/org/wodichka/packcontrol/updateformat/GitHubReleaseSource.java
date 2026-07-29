package org.wodichka.packcontrol.updateformat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackFileResolution.Issue;
import org.wodichka.packcontrol.updateformat.PackFileResolution.IssueCode;
import org.wodichka.packcontrol.updateformat.PackSourceReference.GitHubReleaseReference;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class GitHubReleaseSource implements PackFileSource {
    private static final Pattern REPOSITORY_SEGMENT = Pattern.compile("^[A-Za-z0-9_.-]+$");

    private final URI apiBase;
    private final PackHttpClient http;
    private final MetadataCache<List<AssetMetadata>> cache;

    public GitHubReleaseSource() {
        this(
                URI.create("https://api.github.com/"),
                new PackHttpClient(),
                Duration.ofMinutes(10)
        );
    }

    public GitHubReleaseSource(URI apiBase, PackHttpClient http, Duration cacheTtl) {
        this.apiBase = ensureTrailingSlash(apiBase);
        this.http = http;
        this.cache = new MetadataCache<>(cacheTtl);
    }

    @Override
    public String sourceId() {
        return "github-release";
    }

    @Override
    public boolean supports(PackSourceReference reference) {
        return reference instanceof GitHubReleaseReference;
    }

    @Override
    public Map<String, PackFileResolution> resolve(
            List<PackFileRequest> requests,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        Map<String, PackFileResolution> results = new LinkedHashMap<>();
        for (PackFileRequest request : requests) {
            if (!(request.source() instanceof GitHubReleaseReference reference)) {
                results.put(
                        request.requestId(),
                        unresolved(request, IssueCode.INVALID_REFERENCE, "Request is not a GitHub release reference")
                );
                continue;
            }
            if (!validReference(reference)) {
                results.put(
                        request.requestId(),
                        unresolved(request, IssueCode.INVALID_REFERENCE, "Invalid public GitHub repository reference")
                );
                continue;
            }
            String cacheKey = (reference.owner() + "/" + reference.repository() + "@" + reference.tag())
                    .toLowerCase(Locale.ROOT);
            List<AssetMetadata> assets = cache.get(cacheKey).orElse(null);
            if (assets == null) {
                assets = fetchAssets(reference, cancellation);
                cache.put(cacheKey, assets);
            }
            results.put(request.requestId(), select(request, reference, assets));
        }
        return results;
    }

    private List<AssetMetadata> fetchAssets(
            GitHubReleaseReference reference,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        String path = "repos/" + encode(reference.owner())
                + "/" + encode(reference.repository())
                + "/releases/tags/" + encode(reference.tag());
        PackHttpClient.TextResponse response = http.getJson(
                apiBase.resolve(path),
                Map.of(
                        "Accept", "application/vnd.github+json",
                        "X-GitHub-Api-Version", "2022-11-28"
                ),
                cancellation
        );
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub release metadata returned HTTP " + response.statusCode());
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body());
        } catch (RuntimeException exception) {
            throw new IOException("GitHub returned invalid JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("GitHub release response must be an object");
        }
        JsonObject release = parsed.getAsJsonObject();
        JsonArray assets = release.has("assets") && release.get("assets").isJsonArray()
                ? release.getAsJsonArray("assets")
                : new JsonArray();
        List<AssetMetadata> result = new ArrayList<>();
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String state = string(asset, "state");
            String name = string(asset, "name");
            String url = string(asset, "browser_download_url");
            if (!"uploaded".equals(state) || name == null || url == null) {
                continue;
            }
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    continue;
                }
                result.add(new AssetMetadata(
                        name,
                        uri,
                        longValue(asset, "size", -1),
                        sha256Digest(string(asset, "digest"))
                ));
            } catch (IllegalArgumentException ignored) {
                // Invalid public metadata is ignored and cannot become a candidate.
            }
        }
        return List.copyOf(result);
    }

    private PackFileResolution select(
            PackFileRequest request,
            GitHubReleaseReference reference,
            List<AssetMetadata> assets
    ) {
        Set<String> desiredNames = new LinkedHashSet<>(reference.assetNames());
        if (desiredNames.isEmpty()) {
            int slash = request.path().lastIndexOf('/');
            desiredNames.add(slash >= 0 ? request.path().substring(slash + 1) : request.path());
        }

        List<PackFileCandidate> candidates = new ArrayList<>();
        boolean namedAssetRejected = false;
        for (String desiredName : desiredNames) {
            for (AssetMetadata asset : assets) {
                if (!desiredName.equals(asset.name)) {
                    continue;
                }
                if (asset.size >= 0 && asset.size != request.size()) {
                    namedAssetRejected = true;
                    continue;
                }
                if (asset.sha256 != null
                        && !asset.sha256.equalsIgnoreCase(request.hashes().sha256())) {
                    namedAssetRejected = true;
                    continue;
                }
                candidates.add(new PackFileCandidate(
                        asset.uri,
                        sourceId(),
                        asset.name,
                        asset.size,
                        new Hashes(null, asset.sha256, null),
                        Set.of("github.com", "githubusercontent.com")
                ));
            }
        }
        if (!candidates.isEmpty()) {
            return new PackFileResolution(request.requestId(), candidates, List.of());
        }
        return unresolved(
                request,
                namedAssetRejected ? IssueCode.HASH_MISMATCH : IssueCode.NOT_FOUND,
                namedAssetRejected
                        ? "GitHub release asset metadata does not match expected file"
                        : "GitHub release has no matching uploaded asset"
        );
    }

    private static boolean validReference(GitHubReleaseReference reference) {
        return reference.owner() != null
                && reference.repository() != null
                && reference.tag() != null
                && !reference.tag().isBlank()
                && REPOSITORY_SEGMENT.matcher(reference.owner()).matches()
                && REPOSITORY_SEGMENT.matcher(reference.repository()).matches();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private static String sha256Digest(String digest) {
        if (digest == null || !digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            return null;
        }
        String value = digest.substring("sha256:".length());
        return value.matches("^[0-9a-fA-F]{64}$") ? value : null;
    }

    private static PackFileResolution unresolved(
            PackFileRequest request,
            IssueCode code,
            String message
    ) {
        return new PackFileResolution(request.requestId(), List.of(), List.of(new Issue(code, message)));
    }

    private record AssetMetadata(String name, URI uri, long size, String sha256) {
    }
}
