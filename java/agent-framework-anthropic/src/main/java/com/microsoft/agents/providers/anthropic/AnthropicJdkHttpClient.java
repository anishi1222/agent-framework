// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpRequestBody;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

final class AnthropicJdkHttpClient implements com.anthropic.core.http.HttpClient {
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;

    private final URI endpoint;

    private final AnthropicChatClientOptions options;

    private final ExecutorService executor;

    private final boolean ownsExecutor;

    AnthropicJdkHttpClient(AnthropicChatClientOptions options, ExecutorService executor, boolean ownsExecutor) {
        this.options = Objects.requireNonNull(options, "options");
        this.endpoint = options.endpoint();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        client = HttpClient.newBuilder()
                .connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    @Override
    public com.anthropic.core.http.HttpResponse execute(
            com.anthropic.core.http.HttpRequest request, RequestOptions requestOptions) {
        try {
            return adapt(client.send(adapt(request), HttpResponse.BodyHandlers.ofInputStream()));
        } catch (IOException exception) {
            throw new AnthropicProviderException("transport_io", null, null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AnthropicProviderException("transport_interrupted", null, null, null);
        }
    }

    @Override
    public CompletableFuture<com.anthropic.core.http.HttpResponse> executeAsync(
            com.anthropic.core.http.HttpRequest request, RequestOptions requestOptions) {
        CompletableFuture<com.anthropic.core.http.HttpResponse> result = new CompletableFuture<>();
        client.sendAsync(adapt(request), HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(normalize(failure));
                    } else {
                        result.complete(adapt(response));
                    }
                });
        return result;
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.close();
        }
    }

    private HttpRequest adapt(com.anthropic.core.http.HttpRequest source) {
        URI uri = URI.create(source.url());
        validateUri(uri);
        byte[] body = body(source.body());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(options.timeout());
        for (String name : source.headers().names()) {
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : source.headers().values(name)) {
                builder.header(name, value);
            }
        }
        if (source.body() != null && source.body().contentType() != null) {
            builder.setHeader("Content-Type", source.body().contentType());
        }
        builder.method(
                source.method().name(),
                body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return builder.build();
    }

    private com.anthropic.core.http.HttpResponse adapt(HttpResponse<InputStream> response) {
        Headers.Builder headers = Headers.builder();
        response.headers().map().forEach((name, values) -> headers.put(name, values));
        String contentType = response.headers().firstValue("content-type").orElse("");
        InputStream bounded = contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")
                ? new EventBoundedInputStream(response.body(), options.maxResponseBytes(), options.maxEventBytes())
                : new BoundedInputStream(response.body(), options.maxResponseBytes());
        return new SdkResponse(response.statusCode(), headers.build(), bounded);
    }

    private byte[] body(HttpRequestBody body) {
        if (body == null) {
            return new byte[0];
        }
        try {
            LimitedOutput output = new LimitedOutput(options.maxRequestBytes());
            body.writeTo(output);
            return output.toByteArray();
        } catch (LimitExceeded exception) {
            throw new AnthropicProviderException("request_too_large", null, null, null);
        }
    }

    private void validateUri(URI uri) {
        if (!uri.isAbsolute()
                || uri.getRawUserInfo() != null
                || !Objects.equals(uri.getScheme(), endpoint.getScheme())
                || !Objects.equals(uri.getHost(), endpoint.getHost())
                || uri.getPort() != endpoint.getPort()) {
            throw new AnthropicProviderException("endpoint_escape", null, null, null);
        }
    }

    private static RuntimeException normalize(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            return runtime;
        }
        return new AnthropicProviderException("transport_error", null, null, null);
    }

    private static final class SdkResponse implements com.anthropic.core.http.HttpResponse {
        private final int status;

        private final Headers headers;

        private final InputStream body;

        private SdkResponse(int status, Headers headers, InputStream body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public Headers headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private static class BoundedInputStream extends FilterInputStream {
        private final long maximum;

        private long count;

        private BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        protected void observe(int value) throws IOException {
            // Subclasses can enforce additional framing limits.
        }

        private void increment(int amount) throws IOException {
            if (amount > maximum - count) {
                throw new IOException("Anthropic response exceeded the configured byte limit.");
            }
            count += amount;
        }
    }

    private static final class EventBoundedInputStream extends BoundedInputStream {
        private final long eventMaximum;

        private long eventBytes;

        private int previous = -1;

        private EventBoundedInputStream(InputStream input, long maximum, long eventMaximum) {
            super(input, maximum);
            this.eventMaximum = eventMaximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            track(value);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            for (int index = 0; index < read; index++) {
                track(bytes[offset + index] & 0xff);
            }
            return read;
        }

        private void track(int value) throws IOException {
            if (value < 0) {
                return;
            }
            eventBytes++;
            if (eventBytes > eventMaximum) {
                throw new IOException("Anthropic SSE event exceeded the configured byte limit.");
            }
            if (previous == '\n' && value == '\n') {
                eventBytes = 0;
            }
            previous = value;
        }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int maximum;

        private LimitedOutput(int maximum) {
            super(Math.min(8 * 1024, maximum));
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int length) {
            if (length > maximum - count) {
                throw new LimitExceeded();
            }
        }
    }

    private static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
