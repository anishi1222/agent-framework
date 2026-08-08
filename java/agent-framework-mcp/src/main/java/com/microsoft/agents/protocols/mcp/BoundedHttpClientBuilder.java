// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

final class BoundedHttpClientBuilder implements HttpClient.Builder {
    private final HttpClient.Builder delegate = HttpClient.newBuilder();

    private final int maximumResponseBytes;

    private final AtomicReference<HttpClient> builtClient = new AtomicReference<>();

    BoundedHttpClientBuilder(int maximumResponseBytes) {
        this.maximumResponseBytes = MCPValidation.positive(maximumResponseBytes, "maximumResponseBytes");
    }

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
        delegate.cookieHandler(cookieHandler);
        return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration duration) {
        delegate.connectTimeout(duration);
        return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext sslContext) {
        delegate.sslContext(sslContext);
        return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
        delegate.sslParameters(sslParameters);
        return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
        delegate.executor(executor);
        return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
        delegate.followRedirects(policy);
        return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
        delegate.version(version);
        return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
        delegate.priority(priority);
        return this;
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector proxySelector) {
        delegate.proxy(proxySelector);
        return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
        delegate.authenticator(authenticator);
        return this;
    }

    @Override
    public HttpClient.Builder localAddress(InetAddress localAddress) {
        delegate.localAddress(localAddress);
        return this;
    }

    @Override
    public HttpClient build() {
        HttpClient client = new BoundedHttpClient(delegate.build(), maximumResponseBytes);
        if (!builtClient.compareAndSet(null, client)) {
            client.close();
            throw new IllegalStateException("Bounded HTTP client builder can build only once.");
        }
        return client;
    }

    void closeBuiltClient(Duration timeout) {
        HttpClient client = builtClient.getAndSet(null);
        if (client != null) {
            client.shutdown();
            try {
                if (!client.awaitTermination(timeout)) {
                    client.shutdownNow();
                    client.awaitTermination(timeout);
                }
            } catch (InterruptedException exception) {
                client.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class BoundedHttpClient extends HttpClient {
        private final HttpClient delegate;

        private final int maximumResponseBytes;

        private BoundedHttpClient(HttpClient delegate, int maximumResponseBytes) {
            this.delegate = delegate;
            this.maximumResponseBytes = maximumResponseBytes;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            return delegate.send(request, bounded(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return delegate.sendAsync(request, bounded(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return delegate.sendAsync(
                    request,
                    bounded(request, responseBodyHandler),
                    (initiatingRequest, pushPromiseRequest, acceptor) -> pushPromiseHandler.applyPushPromise(
                            initiatingRequest,
                            pushPromiseRequest,
                            bodyHandler -> acceptor.apply(bounded(pushPromiseRequest, bodyHandler))));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public void shutdownNow() {
            delegate.shutdownNow();
        }

        @Override
        public boolean awaitTermination(Duration duration) throws InterruptedException {
            return delegate.awaitTermination(duration);
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private <T> HttpResponse.BodyHandler<T> bounded(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
            return responseInfo -> {
                int statusCode = responseInfo.statusCode();
                String rejection = "DELETE".equals(request.method())
                                && statusCode != 404
                                && statusCode != 405
                                && (statusCode < 200 || statusCode >= 300)
                        ? "MCP session DELETE failed with HTTP status " + responseInfo.statusCode() + "."
                        : null;
                return new BoundedBodySubscriber<>(bodyHandler.apply(responseInfo), maximumResponseBytes, rejection);
            };
        }
    }

    private static final class BoundedBodySubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;

        private final long maximumBytes;

        private final String rejection;

        private long observedBytes;

        private Flow.Subscription subscription;

        private boolean failed;

        private BoundedBodySubscriber(HttpResponse.BodySubscriber<T> delegate, long maximumBytes, String rejection) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
            this.rejection = rejection;
        }

        @Override
        public CompletableFuture<T> getBody() {
            return delegate.getBody().toCompletableFuture();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejection != null) {
                failed = true;
                subscription.cancel();
                delegate.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {}

                    @Override
                    public void cancel() {}
                });
                delegate.onError(new IOException(rejection));
                return;
            }
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                observedBytes += buffer.remaining();
                if (observedBytes > maximumBytes) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("MCP HTTP response exceeds the configured payload limit."));
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!failed) {
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
            }
        }
    }
}
