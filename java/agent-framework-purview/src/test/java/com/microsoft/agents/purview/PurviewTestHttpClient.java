// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

final class PurviewTestHttpClient extends HttpClient {
    final List<java.net.http.HttpRequest> requests = new ArrayList<>();

    java.util.function.Function<java.net.http.HttpRequest, CompletableFuture<java.net.http.HttpResponse<byte[]>>>
            handler = request -> response(request, 500, "{}");

    static CompletableFuture<java.net.http.HttpResponse<byte[]>> response(
            java.net.http.HttpRequest request, int status, String body) {
        return response(request, status, body, java.util.Map.of());
    }

    static CompletableFuture<java.net.http.HttpResponse<byte[]>> response(
            java.net.http.HttpRequest request, int status, String body, java.util.Map<String, List<String>> headers) {
        return CompletableFuture.completedFuture(new StubResponse(request, status, body, headers));
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.of(Duration.ofSeconds(5));
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_2;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public <T> java.net.http.HttpResponse<T> send(
            java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
            java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler) {
        requests.add(request);
        return (CompletableFuture<java.net.http.HttpResponse<T>>) (CompletableFuture<?>) handler.apply(request);
    }

    @Override
    public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
            java.net.http.HttpRequest request,
            java.net.http.HttpResponse.BodyHandler<T> responseBodyHandler,
            java.net.http.HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return sendAsync(request, responseBodyHandler);
    }

    private record StubResponse(
            java.net.http.HttpRequest request, int statusCode, byte[] body, java.net.http.HttpHeaders headers)
            implements java.net.http.HttpResponse<byte[]> {
        private StubResponse(
                java.net.http.HttpRequest request,
                int statusCode,
                String body,
                java.util.Map<String, List<String>> headers) {
            this(
                    request,
                    statusCode,
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.net.http.HttpHeaders.of(headers, (name, value) -> true));
        }

        @Override
        public Optional<java.net.http.HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
