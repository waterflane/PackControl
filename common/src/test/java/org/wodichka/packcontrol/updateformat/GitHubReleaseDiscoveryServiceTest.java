package org.wodichka.packcontrol.updateformat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService.CheckRequest;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService.CheckStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseDiscoveryServiceTest {
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void stableIgnoresDraftsAndPrereleasesAndReturnsBothAssets() {
        server.createContext("/repos/example/pack/releases", exchange -> respondJson(exchange, 200, """
                [
                  %s,
                  %s,
                  %s
                ]
                """.formatted(
                release("v3.0.0", true, false, "/manifest-3.json", true),
                release("v2.0.0-beta.1", false, true, "/manifest-beta.json", true),
                release("v1.5.0", false, false, "/manifest-stable.json", true)
        )));
        server.createContext("/manifest-stable.json", exchange ->
                respondJson(exchange, 200, manifest("1.5.0")));

        var result = service(Clock.systemUTC()).check(
                request("stable", "1.0.0", Duration.ofMinutes(10)),
                CancellationToken.none()
        );

        assertEquals(CheckStatus.UPDATE_AVAILABLE, result.status());
        assertEquals("1.5.0", result.release().version());
        assertEquals(2, result.release().assets().size());
        assertTrue(result.release().manifestUri().toString().endsWith("/manifest-stable.json"));
        assertTrue(result.release().overridesUri().toString().endsWith("/overrides.zip"));
        assertNotNull(result.release().manifest());
    }

    @Test
    void betaAcceptsPrereleasesAndUsesSemverOrdering() {
        server.createContext("/repos/example/pack/releases", exchange -> respondJson(exchange, 200, """
                [
                  %s,
                  %s
                ]
                """.formatted(
                release("v1.9.0", false, false, "/stable.json", true),
                release("v2.0.0-beta.2", false, true, "/beta.json", true)
        )));
        server.createContext("/beta.json", exchange -> respondJson(exchange, 200, manifest("2.0.0-beta.2")));

        var result = service(Clock.systemUTC()).check(
                request("beta", "2.0.0-beta.1", Duration.ZERO),
                CancellationToken.none()
        );

        assertEquals(CheckStatus.UPDATE_AVAILABLE, result.status());
        assertEquals("2.0.0-beta.2", result.release().version());
        assertTrue(result.release().prerelease());
    }

    @Test
    void manifestMustExistAndPassStrictValidation() {
        server.createContext("/repos/example/pack/releases", exchange -> respondJson(exchange, 200, """
                [
                  %s,
                  %s
                ]
                """.formatted(
                release("v2.0.0", false, false, "/invalid.json", true),
                release("v1.0.0", false, false, "/missing.json", false)
        )));
        server.createContext("/invalid.json", exchange -> respondJson(exchange, 200, """
                {"schemaVersion": 999, "metadata": {"version": "2.0.0"}}
                """));

        var result = service(Clock.systemUTC()).check(
                request("stable", "1.0.0", Duration.ZERO),
                CancellationToken.none()
        );

        assertEquals(CheckStatus.INVALID_RELEASE, result.status());
        assertNull(result.release());
        assertTrue(result.message().contains("manifest"));
    }

    @Test
    void usesIntervalThenEtagConditionalRequest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> conditional = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/repos/example/pack/releases", exchange -> {
            hits.incrementAndGet();
            conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("\"fixture-etag\"".equals(conditional.get())) {
                exchange.getResponseHeaders().add("ETag", "\"fixture-etag\"");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
            } else {
                exchange.getResponseHeaders().add("ETag", "\"fixture-etag\"");
                respondJson(exchange, 200, "[" + release(
                        "v1.1.0", false, false, "/manifest.json", true
                ) + "]");
            }
        });
        server.createContext("/manifest.json", exchange -> respondJson(exchange, 200, manifest("1.1.0")));
        GitHubReleaseDiscoveryService service = service(clock);
        CheckRequest request = request("stable", "1.0.0", Duration.ofMinutes(15));

        var first = service.check(request, CancellationToken.none());
        var intervalCached = service.check(request, CancellationToken.none());
        clock.advance(Duration.ofMinutes(16));
        var etagCached = service.check(request, CancellationToken.none());

        assertEquals(CheckStatus.UPDATE_AVAILABLE, first.status());
        assertFalse(first.fromCache());
        assertTrue(intervalCached.fromCache());
        assertTrue(etagCached.fromCache());
        assertEquals(2, hits.get());
        assertEquals("\"fixture-etag\"", conditional.get());
        assertNull(authorization.get());
    }

    @Test
    void networkFailureIsAResultAndCachedReleaseRemainsUsable() {
        AtomicInteger status = new AtomicInteger(200);
        server.createContext("/repos/example/pack/releases", exchange -> {
            if (status.get() != 200) {
                respondJson(exchange, status.get(), "{\"message\":\"temporary\"}");
                return;
            }
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            respondJson(exchange, 200, "[" + release(
                    "v1.1.0", false, false, "/manifest.json", true
            ) + "]");
        });
        server.createContext("/manifest.json", exchange -> respondJson(exchange, 200, manifest("1.1.0")));
        GitHubReleaseDiscoveryService service = service(Clock.systemUTC());
        CheckRequest request = request("stable", "1.0.0", Duration.ZERO);

        assertEquals(CheckStatus.UPDATE_AVAILABLE, service.check(request, CancellationToken.none()).status());
        status.set(503);
        var cachedFailure = service.check(request, CancellationToken.none());

        assertEquals(CheckStatus.UPDATE_AVAILABLE, cachedFailure.status());
        assertTrue(cachedFailure.fromCache());

        var firstFailure = service(Clock.systemUTC()).check(
                request("stable", "1.0.0", Duration.ZERO),
                CancellationToken.none()
        );
        assertEquals(CheckStatus.NETWORK_ERROR, firstFailure.status());
        assertNull(firstFailure.release());
    }

    @Test
    void equalVersionWithDifferentBuildMetadataIsUpToDate() {
        server.createContext("/repos/example/pack/releases", exchange -> respondJson(exchange, 200, "["
                + release("v1.0.0+remote", false, false, "/manifest.json", true) + "]"));
        server.createContext("/manifest.json", exchange ->
                respondJson(exchange, 200, manifest("1.0.0+remote")));

        var result = service(Clock.systemUTC()).check(
                request("stable", "1.0.0+local", Duration.ZERO),
                CancellationToken.none()
        );

        assertEquals(CheckStatus.UP_TO_DATE, result.status());
    }

    @Test
    void rejectsUnknownChannelAndMalformedRepositoryWithoutNetworkAccess() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/repos/", exchange -> {
            hits.incrementAndGet();
            respondJson(exchange, 500, "{}");
        });
        GitHubReleaseDiscoveryService service = service(Clock.systemUTC());

        assertEquals(
                CheckStatus.INVALID_CONFIGURATION,
                service.check(
                        new CheckRequest("example/pack", "development", "1.0.0", Duration.ZERO),
                        CancellationToken.none()
                ).status()
        );
        assertEquals(
                CheckStatus.INVALID_CONFIGURATION,
                service.check(
                        new CheckRequest("../pack", "stable", "1.0.0", Duration.ZERO),
                        CancellationToken.none()
                ).status()
        );
        assertEquals(0, hits.get());
    }

    private GitHubReleaseDiscoveryService service(Clock clock) {
        PackHttpPolicy policy = new PackHttpPolicy(
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                1,
                1,
                Duration.ZERO,
                "PackControl-Test/1.0"
        );
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new GitHubReleaseDiscoveryService(baseUri, new PackHttpClient(client, policy), clock);
    }

    private CheckRequest request(String channel, String installed, Duration interval) {
        return new CheckRequest("example/pack", channel, installed, interval);
    }

    private String release(
            String tag,
            boolean draft,
            boolean prerelease,
            String manifestPath,
            boolean includeOverrides
    ) {
        String overrides = includeOverrides
                ? "," + asset("overrides.zip", "/overrides.zip", "application/zip")
                : "";
        return """
                {
                  "tag_name": "%s",
                  "name": "%s",
                  "draft": %s,
                  "prerelease": %s,
                  "published_at": "2026-01-01T00:00:00Z",
                  "assets": [
                    %s%s
                  ]
                }
                """.formatted(
                tag,
                tag,
                draft,
                prerelease,
                asset("packcontrol-manifest.json", manifestPath, "application/json"),
                overrides
        );
    }

    private String asset(String name, String path, String contentType) {
        return """
                {
                  "name": "%s",
                  "state": "uploaded",
                  "size": 2048,
                  "content_type": "%s",
                  "browser_download_url": "%s"
                }
                """.formatted(name, contentType, baseUri.resolve(path));
    }

    private static String manifest(String version) {
        return """
                {
                  "schemaVersion": 1,
                  "metadata": {
                    "packId": "fixture",
                    "name": "Fixture",
                    "version": "%s",
                    "releaseId": "fixture-%s",
                    "minecraftVersion": "1.21.1",
                    "loader": "neoforge",
                    "loaderVersion": "21.1.200"
                  },
                  "minimumPackControlVersion": "0.1.0",
                  "files": [],
                  "overrides": {
                    "fileName": "overrides.zip",
                    "downloads": ["https://github.com/example/pack/releases/download/v%s/overrides.zip"],
                    "hashes": {
                      "sha1": "%s",
                      "sha256": "%s",
                      "sha512": "%s"
                    },
                    "size": 2048,
                    "entries": []
                  },
                  "removedFiles": []
                }
                """.formatted(
                version,
                version,
                version,
                "a".repeat(40),
                "b".repeat(64),
                "c".repeat(128)
        );
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
