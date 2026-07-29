package org.wodichka.packcontrol.updateformat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

@FunctionalInterface
public interface ArtifactDownloader {
    DownloadResponse open(URI uri) throws IOException, InterruptedException;

    record DownloadResponse(
            int statusCode,
            long contentLength,
            InputStream body
    ) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
