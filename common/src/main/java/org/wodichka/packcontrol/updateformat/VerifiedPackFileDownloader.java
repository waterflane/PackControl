package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.FileHashing.DigestedContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VerifiedPackFileDownloader {
    private final PackHttpClient http;
    private final boolean requireHttps;

    public VerifiedPackFileDownloader() {
        this(new PackHttpClient(), true);
    }

    public VerifiedPackFileDownloader(PackHttpClient http, boolean requireHttps) {
        this.http = http;
        this.requireHttps = requireHttps;
    }

    public VerifiedDownload download(
            PackFileRequest request,
            PackFileResolution resolution,
            Path target,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        if (!request.requestId().equals(resolution.requestId())) {
            throw new IllegalArgumentException("Resolution does not belong to request " + request.requestId());
        }
        if (!resolution.resolved()) {
            throw new IOException("No download candidates for " + request.path() + ": " + resolution.issues());
        }

        List<String> failures = new ArrayList<>();
        Files.deleteIfExists(target);
        for (PackFileCandidate candidate : resolution.candidates()) {
            try {
                cancellation.throwIfCancelled();
            } catch (CancellationToken.PackRequestCancelledException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
            Files.deleteIfExists(target);
            if (requireHttps && !"https".equalsIgnoreCase(candidate.downloadUri().getScheme())) {
                failures.add(candidate.downloadUri() + " is not HTTPS");
                continue;
            }
            try (PackHttpClient.StreamResponse response = http.openStream(
                    candidate.downloadUri(),
                    Map.of("Accept", "application/octet-stream"),
                    cancellation,
                    uri -> allowed(candidate, uri)
            )) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    failures.add(candidate.downloadUri() + " returned HTTP " + response.statusCode());
                    continue;
                }
                if (response.contentLength() >= 0 && response.contentLength() != request.size()) {
                    failures.add(candidate.downloadUri() + " returned an unexpected Content-Length");
                    continue;
                }
                DigestedContent content = FileHashing.copyAndHash(
                        response.body(),
                        target,
                        request.size()
                );
                if (content.size() != request.size()) {
                    failures.add(candidate.downloadUri() + " returned an unexpected file size");
                    continue;
                }
                if (!hashesEqual(request.hashes(), content.hashes())) {
                    failures.add(candidate.downloadUri() + " failed SHA-1/SHA-256/SHA-512 verification");
                    continue;
                }
                return new VerifiedDownload(target, candidate);
            } catch (CancellationToken.PackRequestCancelledException exception) {
                Files.deleteIfExists(target);
                throw exception;
            } catch (IOException exception) {
                failures.add(candidate.downloadUri() + " failed: " + exception.getMessage());
            }
        }
        Files.deleteIfExists(target);
        throw new IOException("All verified download candidates failed: " + String.join("; ", failures));
    }

    private static boolean hashesEqual(
            PackControlManifest.Hashes expected,
            PackControlManifest.Hashes actual
    ) {
        return expected.sha1().equalsIgnoreCase(actual.sha1())
                && expected.sha256().equalsIgnoreCase(actual.sha256())
                && expected.sha512().equalsIgnoreCase(actual.sha512());
    }

    private static boolean allowed(PackFileCandidate candidate, java.net.URI uri) {
        if (uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return candidate.allowedDomains().stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    public record VerifiedDownload(Path path, PackFileCandidate candidate) {
    }
}
