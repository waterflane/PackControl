package org.wodichka.packcontrol.updateformat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.CancellationToken.PackRequestCancelledException;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackSourceReference.DirectHttpsReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.GitHubReleaseReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.ModrinthReference;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFileSourcesHttpTest {
    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private ExecutorService executor;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        baseUri = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void modrinthUsesOneBatchPostSelectsFilesAndCachesMetadata() throws Exception {
        String firstHash = "1".repeat(128);
        String secondHash = "2".repeat(128);
        String sha1 = "a".repeat(40);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        server.createContext("/version_files", exchange -> {
            hits.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            assertEquals("POST", exchange.getRequestMethod());
            respondJson(exchange, 200, """
                    {
                      "%s": {
                        "files": [
                          {
                            "hashes": {"sha1": "%s", "sha512": "%s"},
                            "url": "https://cdn.modrinth.com/wrong.jar",
                            "filename": "wrong.jar",
                            "primary": true,
                            "size": 11
                          },
                          {
                            "hashes": {"sha1": "%s", "sha512": "%s"},
                            "url": "https://cdn.modrinth.com/preferred.jar",
                            "filename": "preferred.jar",
                            "primary": false,
                            "size": 11
                          }
                        ]
                      },
                      "%s": {
                        "files": [
                          {
                            "hashes": {"sha1": "%s", "sha512": "%s"},
                            "url": "https://cdn.modrinth.com/primary.jar",
                            "filename": "primary.jar",
                            "primary": true,
                            "size": 12
                          }
                        ]
                      }
                    }
                    """.formatted(
                    firstHash, sha1, firstHash, sha1, firstHash,
                    secondHash, sha1, secondHash
            ));
        });

        ModrinthSource source = new ModrinthSource(
                baseUri,
                testHttpClient(2, 2, Duration.ofSeconds(1)),
                Duration.ofMinutes(5),
                100
        );
        PackFileRequest first = request(
                "first",
                "mods/preferred.jar",
                firstHash,
                11,
                new ModrinthReference("preferred.jar")
        );
        PackFileRequest second = request(
                "second",
                "mods/primary.jar",
                secondHash,
                12,
                new ModrinthReference(null)
        );

        Map<String, PackFileResolution> firstResult = source.resolve(
                List.of(first, second),
                CancellationToken.none()
        );
        Map<String, PackFileResolution> cachedResult = source.resolve(
                List.of(first, second),
                CancellationToken.none()
        );

        assertEquals(1, hits.get());
        assertTrue(requestBody.get().contains("\"algorithm\":\"sha512\""));
        assertTrue(requestBody.get().contains(firstHash));
        assertTrue(requestBody.get().contains(secondHash));
        assertEquals("preferred.jar", firstResult.get("first").candidates().getFirst().fileName());
        assertEquals("wrong.jar", firstResult.get("first").candidates().get(1).fileName());
        assertEquals("primary.jar", firstResult.get("second").candidates().getFirst().fileName());
        assertEquals(firstResult, cachedResult);
        assertTrue(userAgent.get().startsWith("PackControl/"));
        assertTrue(userAgent.get().contains("github.com/waterflane/PackControl"));
    }

    @Test
    void githubParsesPublicReleaseSelectsFallbackAssetAndCachesResponse() throws Exception {
        byte[] expected = bytes("github asset");
        Hashes hashes = hashes(expected);
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>("not-observed");
        server.createContext("/repos/owner/repository/releases/tags/v1.0.0", exchange -> {
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respondJson(exchange, 200, """
                    {
                      "tag_name": "v1.0.0",
                      "assets": [
                        {
                          "id": 10,
                          "name": "fallback.jar",
                          "state": "uploaded",
                          "size": %d,
                          "digest": "sha256:%s",
                          "browser_download_url": "https://github.com/owner/repository/releases/download/v1.0.0/fallback.jar"
                        },
                        {
                          "id": 11,
                          "name": "preferred.jar",
                          "state": "uploaded",
                          "size": %d,
                          "digest": "sha256:%s",
                          "browser_download_url": "https://github.com/owner/repository/releases/download/v1.0.0/preferred.jar"
                        },
                        {
                          "id": 12,
                          "name": "draft.jar",
                          "state": "starter",
                          "size": 1,
                          "browser_download_url": "https://github.com/owner/repository/releases/download/v1.0.0/draft.jar"
                        }
                      ]
                    }
                    """.formatted(
                    expected.length,
                    hashes.sha256(),
                    expected.length,
                    hashes.sha256()
            ));
        });

        GitHubReleaseSource source = new GitHubReleaseSource(
                baseUri,
                testHttpClient(2, 2, Duration.ofSeconds(1)),
                Duration.ofMinutes(5)
        );
        PackFileRequest request = new PackFileRequest(
                "github",
                "mods/preferred.jar",
                hashes,
                expected.length,
                new GitHubReleaseReference(
                        "owner",
                        "repository",
                        "v1.0.0",
                        List.of("preferred.jar", "fallback.jar")
                )
        );

        PackFileResolution first = source.resolve(
                List.of(request),
                CancellationToken.none()
        ).get("github");
        PackFileResolution cached = source.resolve(
                List.of(request),
                CancellationToken.none()
        ).get("github");

        assertTrue(first.resolved());
        assertEquals(List.of("preferred.jar", "fallback.jar"), first.candidates().stream()
                .map(PackFileCandidate::fileName)
                .toList());
        assertEquals(first, cached);
        assertEquals(1, hits.get());
        assertEquals(null, authorization.get());
    }

    @Test
    void directHttpsKeepsAllowedFallbackOrderAndRejectsOtherDomains() throws Exception {
        DirectHttpsSource source = new DirectHttpsSource(Set.of("example.com"));
        PackFileRequest request = new PackFileRequest(
                "direct",
                "mods/example.jar",
                hashes(bytes("direct")),
                6,
                new DirectHttpsReference(List.of(
                        URI.create("https://cdn.example.com/first.jar"),
                        URI.create("https://evil.invalid/evil.jar"),
                        URI.create("https://example.com/second.jar"),
                        URI.create("https://cdn.example.com/first.jar"),
                        URI.create("http://example.com/plain.jar")
                ))
        );

        PackFileResolution result = source.resolve(
                List.of(request),
                CancellationToken.none()
        ).get("direct");

        assertEquals(
                List.of(
                        URI.create("https://cdn.example.com/first.jar"),
                        URI.create("https://example.com/second.jar")
                ),
                result.candidates().stream().map(PackFileCandidate::downloadUri).toList()
        );
        assertEquals(2, result.issues().size());
    }

    @Test
    void verifiedDownloaderUsesFallbackRetriesTemporaryErrorAndIgnoresAdvertisedTrust() throws Exception {
        byte[] expected = bytes("verified bytes");
        byte[] tampered = bytes("tampered bytes");
        AtomicInteger temporaryHits = new AtomicInteger();
        AtomicInteger redirectHits = new AtomicInteger();
        server.createContext("/tampered", exchange -> respondBytes(exchange, 200, tampered));
        server.createContext("/redirect", exchange -> {
            redirectHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/temporary");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/temporary", exchange -> {
            if (temporaryHits.incrementAndGet() == 1) {
                respondBytes(exchange, 503, bytes("retry"));
            } else {
                respondBytes(exchange, 200, expected);
            }
        });
        PackFileRequest request = new PackFileRequest(
                "download",
                "mods/example.jar",
                hashes(expected),
                expected.length,
                new DirectHttpsReference(List.of())
        );
        PackFileResolution resolution = new PackFileResolution(
                request.requestId(),
                List.of(
                        new PackFileCandidate(
                                baseUri.resolve("tampered"),
                                "mock-untrusted",
                                "example.jar",
                                expected.length,
                                hashes(expected),
                                Set.of("127.0.0.1")
                        ),
                        new PackFileCandidate(
                                baseUri.resolve("redirect"),
                                "mock-fallback",
                                "example.jar",
                                expected.length,
                                hashes(tampered),
                                Set.of("127.0.0.1")
                        )
                ),
                List.of()
        );
        VerifiedPackFileDownloader downloader = new VerifiedPackFileDownloader(
                testHttpClient(2, 2, Duration.ofSeconds(1)),
                false
        );
        Path target = temporaryDirectory.resolve("example.jar");

        VerifiedPackFileDownloader.VerifiedDownload result = downloader.download(
                request,
                resolution,
                target,
                CancellationToken.none()
        );

        assertEquals("mock-fallback", result.candidate().sourceId());
        assertEquals(expected.length, Files.size(target));
        assertEquals("verified bytes", Files.readString(target));
        assertEquals(2, temporaryHits.get());
        assertEquals(2, redirectHits.get());
    }

    @Test
    void retriesOnlyTemporaryStatusesAndEnforcesRedirectLimit() throws Exception {
        AtomicInteger temporaryHits = new AtomicInteger();
        AtomicInteger permanentHits = new AtomicInteger();
        server.createContext("/temporary-json", exchange -> {
            if (temporaryHits.incrementAndGet() == 1) {
                respondJson(exchange, 503, "{}");
            } else {
                respondJson(exchange, 200, "{\"ok\":true}");
            }
        });
        server.createContext("/permanent-json", exchange -> {
            permanentHits.incrementAndGet();
            respondJson(exchange, 404, "{}");
        });
        server.createContext("/redirect-1", exchange -> redirect(exchange, "/redirect-2"));
        server.createContext("/redirect-2", exchange -> redirect(exchange, "/redirect-3"));
        server.createContext("/redirect-3", exchange -> respondJson(exchange, 200, "{}"));

        PackHttpClient normal = testHttpClient(2, 2, Duration.ofSeconds(1));
        assertEquals(200, normal.getJson(
                baseUri.resolve("temporary-json"),
                Map.of(),
                CancellationToken.none()
        ).statusCode());
        assertEquals(2, temporaryHits.get());

        assertEquals(404, normal.getJson(
                baseUri.resolve("permanent-json"),
                Map.of(),
                CancellationToken.none()
        ).statusCode());
        assertEquals(1, permanentHits.get());

        PackHttpClient oneRedirect = testHttpClient(1, 1, Duration.ofSeconds(1));
        assertThrows(IOException.class, () -> oneRedirect.getJson(
                baseUri.resolve("redirect-1"),
                Map.of(),
                CancellationToken.none()
        ));
    }

    @Test
    void rejectsRedirectThatEscapesCandidateDomainAllowlist() throws Exception {
        AtomicInteger escapedTargetHits = new AtomicInteger();
        server.createContext("/escape", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location",
                    "http://localhost:" + server.getAddress().getPort() + "/escaped-target"
            );
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/escaped-target", exchange -> {
            escapedTargetHits.incrementAndGet();
            respondBytes(exchange, 200, bytes("payload"));
        });
        byte[] expected = bytes("payload");
        PackFileRequest request = new PackFileRequest(
                "redirect-escape",
                "mods/escape.jar",
                hashes(expected),
                expected.length,
                new DirectHttpsReference(List.of())
        );
        PackFileResolution resolution = new PackFileResolution(
                request.requestId(),
                List.of(new PackFileCandidate(
                        baseUri.resolve("escape"),
                        "direct-https",
                        "escape.jar",
                        expected.length,
                        hashes(expected),
                        Set.of("127.0.0.1")
                )),
                List.of()
        );

        assertThrows(IOException.class, () -> new VerifiedPackFileDownloader(
                testHttpClient(3, 3, Duration.ofSeconds(1)),
                false
        ).download(
                request,
                resolution,
                temporaryDirectory.resolve("escape.jar"),
                CancellationToken.none()
        ));
        assertEquals(0, escapedTargetHits.get());
    }

    @Test
    void enforcesTimeoutAndCancellation() {
        AtomicInteger cancelledHits = new AtomicInteger();
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(250);
                respondJson(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.createContext("/cancelled", exchange -> {
            cancelledHits.incrementAndGet();
            respondJson(exchange, 200, "{}");
        });
        PackHttpClient shortTimeout = testHttpClient(1, 1, Duration.ofMillis(50));

        assertThrows(IOException.class, () -> shortTimeout.getJson(
                baseUri.resolve("slow"),
                Map.of(),
                CancellationToken.none()
        ));

        CancellationToken cancellation = new CancellationToken();
        cancellation.cancel();
        assertThrows(PackRequestCancelledException.class, () -> shortTimeout.getJson(
                baseUri.resolve("cancelled"),
                Map.of(),
                cancellation
        ));
        assertEquals(0, cancelledHits.get());
    }

    @Test
    void cancellationStopsAnActiveDownloadAndRemovesPartialFile() throws Exception {
        byte[] expected = bytes("first-part-and-second-part");
        server.createContext("/stream", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(expected, 0, 5);
                exchange.getResponseBody().flush();
                Thread.sleep(500);
                exchange.getResponseBody().write(expected, 5, expected.length - 5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        PackFileRequest request = new PackFileRequest(
                "cancel-active",
                "mods/cancel.jar",
                hashes(expected),
                expected.length,
                new DirectHttpsReference(List.of())
        );
        PackFileResolution resolution = new PackFileResolution(
                request.requestId(),
                List.of(new PackFileCandidate(
                        baseUri.resolve("stream"),
                        "mock",
                        "cancel.jar",
                        expected.length,
                        hashes(expected),
                        Set.of("127.0.0.1")
                )),
                List.of()
        );
        CancellationToken cancellation = new CancellationToken();
        Path target = temporaryDirectory.resolve("cancel.jar");
        executor.submit(() -> {
            try {
                Thread.sleep(75);
                cancellation.cancel();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThrows(PackRequestCancelledException.class, () ->
                new VerifiedPackFileDownloader(
                        testHttpClient(1, 1, Duration.ofSeconds(2)),
                        false
                ).download(request, resolution, target, cancellation)
        );
        assertFalse(Files.exists(target));
    }

    private PackHttpClient testHttpClient(
            int maxRedirects,
            int maxAttempts,
            Duration requestTimeout
    ) {
        return new PackHttpClient(new PackHttpPolicy(
                Duration.ofSeconds(1),
                requestTimeout,
                maxRedirects,
                maxAttempts,
                Duration.ZERO,
                PackHttpPolicy.defaults().userAgent()
        ));
    }

    private static PackFileRequest request(
            String id,
            String path,
            String sha512,
            long size,
            PackSourceReference reference
    ) {
        return new PackFileRequest(
                id,
                path,
                new Hashes("a".repeat(40), "b".repeat(64), sha512),
                size,
                reference
        );
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Hashes hashes(byte[] content) {
        return new Hashes(
                digest("SHA-1", content),
                digest("SHA-256", content),
                digest("SHA-512", content)
        );
    }

    private static String digest(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
