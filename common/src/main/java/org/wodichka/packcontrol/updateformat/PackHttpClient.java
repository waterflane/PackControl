package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.CancellationToken.PackRequestCancelledException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

public final class PackHttpClient {
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final Set<Integer> TEMPORARY_STATUSES = Set.of(408, 425, 429, 500, 502, 503, 504);

    private final HttpClient client;
    private final PackHttpPolicy policy;

    public PackHttpClient() {
        this(PackHttpPolicy.defaults());
    }

    public PackHttpClient(PackHttpPolicy policy) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(policy.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                policy
        );
    }

    public PackHttpClient(HttpClient client, PackHttpPolicy policy) {
        this.client = client;
        this.policy = policy;
    }

    public TextResponse getJson(
            URI uri,
            Map<String, String> headers,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        return sendText(new RequestSpec("GET", uri, null, headers), cancellation);
    }

    public TextResponse postJson(
            URI uri,
            String json,
            Map<String, String> headers,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        return sendText(new RequestSpec("POST", uri, json, headers), cancellation);
    }

    public StreamResponse openStream(
            URI uri,
            Map<String, String> headers,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        return openStream(uri, headers, cancellation, ignored -> true);
    }

    public StreamResponse openStream(
            URI uri,
            Map<String, String> headers,
            CancellationToken cancellation,
            Predicate<URI> allowedUri
    ) throws IOException, InterruptedException {
        if (!allowedUri.test(uri)) {
            throw new IOException("Download URI is outside the source allowlist: " + uri);
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellation.throwIfCancelled();
            try {
                StreamResponse response = sendStreamOnce(
                        new RequestSpec("GET", uri, null, headers),
                        cancellation,
                        allowedUri
                );
                if (isTemporary(response.statusCode()) && attempt < policy.maxAttempts()) {
                    response.close();
                    waitBeforeRetry(cancellation);
                    continue;
                }
                return response;
            } catch (PackRequestCancelledException exception) {
                throw exception;
            } catch (PermanentHttpException exception) {
                throw exception;
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt >= policy.maxAttempts()) {
                    throw exception;
                }
                waitBeforeRetry(cancellation);
            }
        }
        throw lastFailure == null ? new IOException("HTTP request failed") : lastFailure;
    }

    private TextResponse sendText(
            RequestSpec request,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellation.throwIfCancelled();
            try {
                TextResponse response = sendTextOnce(request, cancellation);
                if (isTemporary(response.statusCode()) && attempt < policy.maxAttempts()) {
                    waitBeforeRetry(cancellation);
                    continue;
                }
                return response;
            } catch (PackRequestCancelledException exception) {
                throw exception;
            } catch (PermanentHttpException exception) {
                throw exception;
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt >= policy.maxAttempts()) {
                    throw exception;
                }
                waitBeforeRetry(cancellation);
            }
        }
        throw lastFailure == null ? new IOException("HTTP request failed") : lastFailure;
    }

    private TextResponse sendTextOnce(
            RequestSpec initial,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        RequestSpec request = initial;
        for (int redirects = 0; ; redirects++) {
            HttpResponse<String> response = send(
                    buildRequest(request),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                    cancellation
            );
            if (!isRedirect(response.statusCode())) {
                return new TextResponse(response.statusCode(), response.body(), response.uri());
            }
            if (redirects >= policy.maxRedirects()) {
                throw new PermanentHttpException("HTTP redirect limit exceeded for " + initial.uri);
            }
            request = redirected(request, response);
        }
    }

    private StreamResponse sendStreamOnce(
            RequestSpec initial,
            CancellationToken cancellation,
            Predicate<URI> allowedUri
    ) throws IOException, InterruptedException {
        RequestSpec request = initial;
        for (int redirects = 0; ; redirects++) {
            HttpResponse<InputStream> response = send(
                    buildRequest(request),
                    HttpResponse.BodyHandlers.ofInputStream(),
                    cancellation
            );
            if (!isRedirect(response.statusCode())) {
                long contentLength = response.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1);
                return new StreamResponse(
                        response.statusCode(),
                        contentLength,
                        new CancellationAwareInputStream(response.body(), cancellation),
                        response.uri()
                );
            }
            response.body().close();
            if (redirects >= policy.maxRedirects()) {
                throw new PermanentHttpException("HTTP redirect limit exceeded for " + initial.uri);
            }
            request = redirected(request, response, allowedUri);
        }
    }

    private HttpRequest buildRequest(RequestSpec request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri)
                .timeout(policy.requestTimeout())
                .header("User-Agent", policy.userAgent());
        request.headers.forEach((name, value) -> {
            if ("Authorization".equalsIgnoreCase(name) || "User-Agent".equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("PackControl source may not override " + name);
            }
            builder.header(name, value);
        });
        if ("POST".equals(request.method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(request.body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return builder.build();
    }

    private RequestSpec redirected(RequestSpec request, HttpResponse<?> response) throws IOException {
        return redirected(request, response, ignored -> true);
    }

    private RequestSpec redirected(
            RequestSpec request,
            HttpResponse<?> response,
            Predicate<URI> allowedUri
    ) throws IOException {
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty()) {
            throw new PermanentHttpException("Redirect response is missing Location header");
        }
        URI target = request.uri.resolve(location.get());
        String scheme = target.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new PermanentHttpException("Unsupported redirect scheme: " + scheme);
        }
        if ("https".equalsIgnoreCase(request.uri.getScheme()) && !"https".equalsIgnoreCase(scheme)) {
            throw new PermanentHttpException("HTTPS downgrade redirect is forbidden");
        }
        if (!allowedUri.test(target)) {
            throw new PermanentHttpException("Redirect target is outside the source allowlist: " + target);
        }
        boolean switchToGet = response.statusCode() == 303
                || (("POST".equals(request.method))
                && (response.statusCode() == 301 || response.statusCode() == 302));
        return new RequestSpec(
                switchToGet ? "GET" : request.method,
                target,
                switchToGet ? null : request.body,
                request.headers
        );
    }

    private <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            CancellationToken cancellation
    ) throws IOException, InterruptedException {
        cancellation.throwIfCancelled();
        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, handler);
        try (CancellationToken.Registration ignored = cancellation.onCancel(() -> future.cancel(true))) {
            try {
                return future.get();
            } catch (InterruptedException exception) {
                future.cancel(true);
                throw exception;
            } catch (CancellationException exception) {
                throw new PackRequestCancelledException();
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof InterruptedException interruptedException) {
                    throw interruptedException;
                }
                throw new IOException("HTTP request failed: " + cause.getMessage(), cause);
            }
        }
    }

    private void waitBeforeRetry(CancellationToken cancellation) throws IOException, InterruptedException {
        if (!policy.retryDelay().isZero()) {
            cancellation.waitBeforeRetry(policy.retryDelay().toMillis());
        }
    }

    private static boolean isRedirect(int status) {
        return REDIRECT_STATUSES.contains(status);
    }

    private static boolean isTemporary(int status) {
        return TEMPORARY_STATUSES.contains(status);
    }

    public record TextResponse(int statusCode, String body, URI effectiveUri) {
    }

    public record StreamResponse(
            int statusCode,
            long contentLength,
            InputStream body,
            URI effectiveUri
    ) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private record RequestSpec(
            String method,
            URI uri,
            String body,
            Map<String, String> headers
    ) {
    }

    private static final class CancellationAwareInputStream extends InputStream {
        private final InputStream delegate;
        private final CancellationToken cancellation;
        private final CancellationToken.Registration registration;

        private CancellationAwareInputStream(InputStream delegate, CancellationToken cancellation) {
            this.delegate = delegate;
            this.cancellation = cancellation;
            this.registration = cancellation.onCancel(() -> {
                try {
                    delegate.close();
                } catch (IOException ignored) {
                    // A blocked read will observe cancellation or the close failure.
                }
            });
        }

        @Override
        public int read() throws IOException {
            cancellation.throwIfCancelled();
            try {
                int value = delegate.read();
                cancellation.throwIfCancelled();
                return value;
            } catch (IOException exception) {
                cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            cancellation.throwIfCancelled();
            try {
                int read = delegate.read(buffer, offset, length);
                cancellation.throwIfCancelled();
                return read;
            } catch (IOException exception) {
                cancellation.throwIfCancelled();
                throw exception;
            }
        }

        @Override
        public void close() throws IOException {
            registration.close();
            delegate.close();
        }
    }

    private static final class PermanentHttpException extends IOException {
        private PermanentHttpException(String message) {
            super(message);
        }
    }
}
