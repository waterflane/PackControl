package org.wodichka.packcontrol.updateformat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

public final class HttpArtifactDownloader implements ArtifactDownloader {
    private final PackHttpClient client;

    public HttpArtifactDownloader() {
        this.client = new PackHttpClient();
    }

    public HttpArtifactDownloader(HttpClient client, Duration requestTimeout) {
        PackHttpPolicy defaults = PackHttpPolicy.defaults();
        this.client = new PackHttpClient(
                client,
                new PackHttpPolicy(
                        defaults.connectTimeout(),
                        requestTimeout,
                        defaults.maxRedirects(),
                        defaults.maxAttempts(),
                        defaults.retryDelay(),
                        defaults.userAgent()
                )
        );
    }

    @Override
    public DownloadResponse open(URI uri) throws IOException, InterruptedException {
        PackHttpClient.StreamResponse response = client.openStream(
                uri,
                Map.of(),
                CancellationToken.none()
        );
        return new DownloadResponse(
                response.statusCode(),
                response.contentLength(),
                response.body()
        );
    }
}
