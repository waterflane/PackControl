package org.wodichka.packcontrol.updateformat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only GitHub Releases discovery. It never downloads pack payloads and
 * never invokes the update planner or installer.
 */
public final class GitHubReleaseDiscoveryService {
    public static final String MANIFEST_ASSET = "packcontrol-manifest.json";
    public static final String OVERRIDES_ASSET = "overrides.zip";
    private static final int MAX_MANIFEST_CHARACTERS = 4 * 1024 * 1024;
    private static final Pattern REPOSITORY_PART = Pattern.compile("^[A-Za-z0-9_.-]+$");

    private final URI apiBase;
    private final PackHttpClient http;
    private final Clock clock;
    private final ManifestValidator validator;
    private final Map<CacheKey, CacheEntry> cache = new HashMap<>();

    public GitHubReleaseDiscoveryService() {
        this(URI.create("https://api.github.com/"), new PackHttpClient(), Clock.systemUTC());
    }

    public GitHubReleaseDiscoveryService(URI apiBase, PackHttpClient http, Clock clock) {
        this.apiBase = trailingSlash(Objects.requireNonNull(apiBase, "apiBase"));
        if (!secureApiBase(this.apiBase)) {
            throw new IllegalArgumentException("GitHub API base must use HTTPS");
        }
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validator = new ManifestValidator();
    }

    public synchronized CheckResult check(CheckRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Instant now = clock.instant();
        String repository = normalizeRepository(request.repository());
        ReleaseChannel channel = ReleaseChannel.parse(request.channel()).orElse(null);
        if (repository == null || channel == null) {
            return new CheckResult(
                    CheckStatus.INVALID_CONFIGURATION,
                    null,
                    false,
                    now,
                    "Repository must be owner/name and channel must be stable or beta"
            );
        }

        Duration interval = request.minimumInterval() == null
                ? Duration.ofMinutes(15)
                : request.minimumInterval();
        if (interval.isNegative()) {
            return new CheckResult(
                    CheckStatus.INVALID_CONFIGURATION,
                    null,
                    false,
                    now,
                    "Check interval must not be negative"
            );
        }
        CacheKey key = new CacheKey(repository, channel, request.installedVersion());
        CacheEntry existing = cache.get(key);
        if (existing != null && now.isBefore(existing.checkedAt.plus(interval))) {
            return cached(existing, "Using recent release check");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        if (existing != null && existing.etag != null && !existing.etag.isBlank()) {
            headers.put("If-None-Match", existing.etag);
        }

        try {
            PackHttpClient.TextResponse response = http.getJson(
                    releasesUri(repository),
                    headers,
                    cancellation
            );
            if (response.statusCode() == 304) {
                if (existing == null) {
                    return new CheckResult(
                            CheckStatus.NETWORK_ERROR,
                            null,
                            false,
                            now,
                            "GitHub returned 304 without a cached release list"
                    );
                }
                CacheEntry refreshed = new CacheEntry(
                        response.etag() == null ? existing.etag : response.etag(),
                        now,
                        existing.result
                );
                cache.put(key, refreshed);
                return cached(refreshed, "GitHub release list is unchanged");
            }
            if (response.statusCode() != 200) {
                return failure(existing, now, "GitHub Releases returned HTTP " + response.statusCode());
            }

            CheckResult result = selectRelease(
                    response.body(),
                    channel,
                    request.installedVersion(),
                    cancellation,
                    now
            );
            cache.put(key, new CacheEntry(response.etag(), now, result));
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(existing, now, "GitHub release check was interrupted");
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            return failure(existing, now, "GitHub release check failed: " + exception.getMessage());
        }
    }

    private CheckResult selectRelease(
            String json,
            ReleaseChannel channel,
            String installedVersion,
            CancellationToken cancellation,
            Instant checkedAt
    ) throws IOException, InterruptedException {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new JsonParseException("GitHub releases response must be an array");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            if (booleanValue(release, "draft")) {
                continue;
            }
            boolean prerelease = booleanValue(release, "prerelease");
            Optional<SemanticVersion> version = SemanticVersion.tryParseTag(stringValue(release, "tag_name"));
            if (version.isEmpty()) {
                continue;
            }
            if (channel == ReleaseChannel.STABLE && (prerelease || version.get().isPrerelease())) {
                continue;
            }
            candidates.add(new Candidate(release, version.get(), prerelease));
        }
        candidates.sort(Comparator.comparing(Candidate::version).reversed());

        List<String> rejected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ParsedAssets assets = parseAssets(candidate.release);
            if (assets.manifest == null || assets.overrides == null) {
                rejected.add(candidate.version + " is missing required release assets");
                continue;
            }
            PackHttpClient.TextResponse manifestResponse = http.getJson(
                    assets.manifest.downloadUri(),
                    Map.of("Accept", "application/json"),
                    cancellation
            );
            if (manifestResponse.statusCode() != 200) {
                throw new IOException("Manifest asset returned HTTP " + manifestResponse.statusCode());
            }
            if (manifestResponse.body().length() > MAX_MANIFEST_CHARACTERS) {
                rejected.add(candidate.version + " manifest exceeds size limit");
                continue;
            }
            PackControlManifest manifest;
            try {
                manifest = ManifestJson.fromJson(manifestResponse.body());
            } catch (RuntimeException exception) {
                rejected.add(candidate.version + " manifest JSON is invalid");
                continue;
            }
            ManifestValidationResult validation = validator.validate(manifest);
            if (!validation.isValid()) {
                rejected.add(candidate.version + " manifest failed validation at "
                        + validation.errors().getFirst().pointer());
                continue;
            }
            Optional<SemanticVersion> manifestVersion =
                    SemanticVersion.tryParse(manifest.metadata().version());
            if (manifestVersion.isEmpty() || manifestVersion.get().compareTo(candidate.version) != 0) {
                rejected.add(candidate.version + " tag does not match manifest version");
                continue;
            }
            Release release = new Release(
                    stringValue(candidate.release, "tag_name"),
                    candidate.version.toString(),
                    stringValue(candidate.release, "name"),
                    candidate.prerelease,
                    stringValue(candidate.release, "published_at"),
                    assets.assets,
                    assets.manifest.downloadUri(),
                    assets.overrides.downloadUri(),
                    manifest
            );
            Optional<SemanticVersion> installed = SemanticVersion.tryParse(installedVersion);
            boolean update = installed.isEmpty() || candidate.version.compareTo(installed.get()) > 0;
            return new CheckResult(
                    update ? CheckStatus.UPDATE_AVAILABLE : CheckStatus.UP_TO_DATE,
                    release,
                    false,
                    checkedAt,
                    update
                            ? "Pack update " + candidate.version + " is available"
                            : "Installed pack is up to date"
            );
        }
        return new CheckResult(
                candidates.isEmpty() ? CheckStatus.NO_MATCHING_RELEASE : CheckStatus.INVALID_RELEASE,
                null,
                false,
                checkedAt,
                candidates.isEmpty()
                        ? "No release matches channel " + channel.configValue
                        : "No valid PackControl release: " + String.join("; ", rejected)
        );
    }

    private ParsedAssets parseAssets(JsonObject release) {
        List<ReleaseAsset> assets = new ArrayList<>();
        JsonArray jsonAssets = release.getAsJsonArray("assets");
        if (jsonAssets != null) {
            for (JsonElement element : jsonAssets) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject asset = element.getAsJsonObject();
                if (asset.has("state") && !"uploaded".equals(stringValue(asset, "state"))) {
                    continue;
                }
                String name = stringValue(asset, "name");
                String url = stringValue(asset, "browser_download_url");
                URI uri = safeAssetUri(url);
                if (name == null || uri == null) {
                    continue;
                }
                assets.add(new ReleaseAsset(
                        name,
                        uri,
                        longValue(asset, "size"),
                        stringValue(asset, "content_type")
                ));
            }
        }
        ReleaseAsset manifest = uniqueNamed(assets, MANIFEST_ASSET);
        ReleaseAsset overrides = uniqueNamed(assets, OVERRIDES_ASSET);
        return new ParsedAssets(List.copyOf(assets), manifest, overrides);
    }

    private URI safeAssetUri(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                return uri;
            }
            if ("http".equalsIgnoreCase(apiBase.getScheme())
                    && uri.getHost() != null
                    && (uri.getHost().equals("127.0.0.1") || uri.getHost().equalsIgnoreCase("localhost"))) {
                return uri;
            }
            return null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static ReleaseAsset uniqueNamed(List<ReleaseAsset> assets, String name) {
        ReleaseAsset found = null;
        for (ReleaseAsset asset : assets) {
            if (!name.equals(asset.name())) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = asset;
        }
        return found;
    }

    private URI releasesUri(String repository) {
        return apiBase.resolve("repos/" + repository + "/releases?per_page=100");
    }

    private CheckResult failure(CacheEntry existing, Instant now, String message) {
        if (existing != null) {
            return new CheckResult(
                    existing.result.status,
                    existing.result.release,
                    true,
                    existing.checkedAt,
                    message + "; using cached result"
            );
        }
        return new CheckResult(CheckStatus.NETWORK_ERROR, null, false, now, message);
    }

    private static CheckResult cached(CacheEntry entry, String message) {
        return new CheckResult(
                entry.result.status,
                entry.result.release,
                true,
                entry.checkedAt,
                message
        );
    }

    private static String normalizeRepository(String repository) {
        if (repository == null) {
            return null;
        }
        String[] parts = repository.trim().split("/", -1);
        if (parts.length != 2
                || !REPOSITORY_PART.matcher(parts[0]).matches()
                || !REPOSITORY_PART.matcher(parts[1]).matches()
                || parts[0].chars().allMatch(character -> character == '.')
                || parts[1].chars().allMatch(character -> character == '.')) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }

    private static URI trailingSlash(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static boolean secureApiBase(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
            return true;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && (uri.getHost().equals("127.0.0.1") || uri.getHost().equalsIgnoreCase("localhost"));
    }

    private static String stringValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean booleanValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static long longValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? -1 : value.getAsLong();
    }

    public enum ReleaseChannel {
        STABLE("stable"),
        BETA("beta");

        private final String configValue;

        ReleaseChannel(String configValue) {
            this.configValue = configValue;
        }

        public static Optional<ReleaseChannel> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "stable" -> Optional.of(STABLE);
                case "beta" -> Optional.of(BETA);
                default -> Optional.empty();
            };
        }
    }

    public enum CheckStatus {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        NO_MATCHING_RELEASE,
        INVALID_RELEASE,
        INVALID_CONFIGURATION,
        NETWORK_ERROR
    }

    public record CheckRequest(
            String repository,
            String channel,
            String installedVersion,
            Duration minimumInterval
    ) {
    }

    public record CheckResult(
            CheckStatus status,
            Release release,
            boolean fromCache,
            Instant checkedAt,
            String message
    ) {
        public boolean updateAvailable() {
            return status == CheckStatus.UPDATE_AVAILABLE;
        }
    }

    public record Release(
            String tag,
            String version,
            String name,
            boolean prerelease,
            String publishedAt,
            List<ReleaseAsset> assets,
            URI manifestUri,
            URI overridesUri,
            PackControlManifest manifest
    ) {
        public Release {
            assets = List.copyOf(assets);
        }
    }

    public record ReleaseAsset(String name, URI downloadUri, long size, String contentType) {
    }

    private record Candidate(JsonObject release, SemanticVersion version, boolean prerelease) {
    }

    private record ParsedAssets(
            List<ReleaseAsset> assets,
            ReleaseAsset manifest,
            ReleaseAsset overrides
    ) {
    }

    private record CacheKey(String repository, ReleaseChannel channel, String installedVersion) {
    }

    private record CacheEntry(String etag, Instant checkedAt, CheckResult result) {
    }
}
