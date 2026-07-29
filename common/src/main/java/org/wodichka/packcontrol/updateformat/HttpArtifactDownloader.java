package org.wodichka.packcontrol.updateformat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpArtifactDownloader implements ArtifactDownloader {
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpArtifactDownloader() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Duration.ofSeconds(60)
        );
    }

    public HttpArtifactDownloader(HttpClient client, Duration requestTimeout) {
        this.client = client;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public DownloadResponse open(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "waterflane/PackControl/0.1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return new DownloadResponse(response.statusCode(), contentLength, response.body());
    }
}
