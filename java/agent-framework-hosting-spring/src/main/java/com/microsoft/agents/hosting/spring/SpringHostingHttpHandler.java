// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpResponse;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

final class SpringHostingHttpHandler {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final HostingHttpHandler handler;

    private final HostingJsonCodec codec;

    private final HostingLimits limits;

    SpringHostingHttpHandler(HostingHttpHandler handler, HostingJsonCodec codec, HostingLimits limits) {
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    Mono<ServerResponse> handle(ServerRequest request) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        return readBody(request)
                .map(body -> toHostingRequest(request, body, cancellation))
                .flatMap(hostingRequest -> Mono.fromCompletionStage(handler.handleAsync(hostingRequest)))
                .flatMap(this::toServerResponse)
                .onErrorResume(this::errorResponse)
                .doOnCancel(cancellation::cancel);
    }

    private Mono<byte[]> readBody(ServerRequest request) {
        long contentLength = request.headers().contentLength().orElse(-1L);
        if (contentLength > limits.maxRequestBytes()) {
            return Mono.error(payloadTooLarge());
        }
        int maximum = (int) Math.min(Integer.MAX_VALUE - 1L, limits.maxRequestBytes());
        return DataBufferUtils.join(request.bodyToFlux(DataBuffer.class), maximum)
                .map(buffer -> {
                    try {
                        byte[] body = new byte[buffer.readableByteCount()];
                        buffer.read(body);
                        return body;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(DataBufferLimitException.class, ignored -> payloadTooLarge());
    }

    private HostingHttpRequest toHostingRequest(
            ServerRequest request, byte[] body, DefaultRunCancellation cancellation) {
        InetSocketAddress remote = request.remoteAddress()
                .orElseThrow(() -> new HostingException(
                        HostingErrorCode.MALFORMED_REQUEST, "Hosting request peer address is unavailable."));
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        request.headers().asHttpHeaders().forEach(headers::put);
        String path = request.requestPath().pathWithinApplication().value();
        String query = request.uri().getRawQuery();
        URI hostingUri = URI.create(path + (query == null ? "" : "?" + query));
        return new HostingHttpRequest(request.method().name(), hostingUri, remote, headers, body, cancellation);
    }

    private Mono<ServerResponse> toServerResponse(HostingHttpResponse response) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(response.status());
        builder.headers(headers -> copyHeaders(response.headers(), headers));
        Mono<ServerResponse> serverResponse;
        if (!response.isStreaming()) {
            serverResponse = builder.body(BodyInserters.fromValue(response.body()));
        } else {
            serverResponse = builder.body(sse(response), SSE_TYPE);
        }
        return serverResponse
                .map(delegate -> (ServerResponse) new DeliveryAwareServerResponse(delegate, response))
                .doOnError(ignored -> response.discardUndeliveredOutcome());
    }

    private Flux<ServerSentEvent<String>> sse(HostingHttpResponse response) {
        HostingRun run = response.streamingRun();
        AtomicLong sequence = new AtomicLong();
        Flux<ServerSentEvent<String>> source = JdkFlowAdapter.flowPublisherToFlux(run.events())
                .map(event -> frame(sequence.getAndIncrement(), event.type().value(), codec.encodeEvent(event)))
                .onErrorResume(ignored -> Flux.empty());
        Mono<ServerSentEvent<String>> terminal = Mono.fromCompletionStage(run::terminalAsync)
                .map(outcome -> terminalFrame(response, sequence.getAndIncrement(), outcome));
        Flux<ServerSentEvent<String>> frames =
                Flux.concat(Flux.just(startFrame(run, sequence.getAndIncrement())), source, terminal);
        return frames.timeout(limits.idleTimeout(), Flux.defer(() -> {
                    run.cancel();
                    return Mono.fromCompletionStage(run::terminalAsync)
                            .map(outcome -> terminalFrame(response, sequence.getAndIncrement(), outcome));
                }))
                .doOnCancel(run::cancel)
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL || signal == SignalType.ON_ERROR) {
                        run.cancel();
                    }
                });
    }

    private ServerSentEvent<String> terminalFrame(HostingHttpResponse response, long sequence, HostingOutcome outcome) {
        HostingRun run = response.streamingRun();
        try {
            return frame(sequence, "terminal", handler.encodeOutcome(outcome, response));
        } catch (RuntimeException failure) {
            response.confirmDelivery();
            HostingOutcome overflow = HostingOutcome.overflow(
                    run.runId(),
                    HostingError.of(
                            HostingErrorCode.OVERFLOW, "Spring SSE terminal outcome exceeded transport limits."));
            return frame(sequence, "terminal", handler.encodeOutcome(overflow));
        }
    }

    private ServerSentEvent<String> startFrame(HostingRun run, long sequence) {
        StateValue value = StateValue.object(Map.of(
                "version",
                StateValue.string(HostingJsonCodec.WIRE_VERSION),
                "type",
                StateValue.string("event"),
                "event",
                StateValue.string(HostingEventType.RUN_STARTED.value()),
                "runId",
                StateValue.string(run.runId()),
                "createdAt",
                StateValue.string(Instant.now().toString()),
                "data",
                StateValue.object(Map.of())));
        return frame(sequence, "run-started", codec.encodeValue(value));
    }

    private static ServerSentEvent<String> frame(long sequence, String event, byte[] data) {
        return ServerSentEvent.<String>builder()
                .id(Long.toString(sequence))
                .event(event)
                .data(new String(data, StandardCharsets.UTF_8))
                .build();
    }

    private Mono<ServerResponse> errorResponse(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        HostingError error = cause instanceof HostingException hosting
                ? hosting.error()
                : HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosting request failed.");
        ServerResponse.BodyBuilder builder = ServerResponse.status(error.code().httpStatus())
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY");
        if (error.code() == HostingErrorCode.UNAUTHENTICATED) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return builder.body(BodyInserters.fromValue(handler.encodeError(error)));
    }

    private HostingException payloadTooLarge() {
        return new HostingException(
                HostingErrorCode.PAYLOAD_TOO_LARGE,
                "Request exceeds maxRequestBytes " + limits.maxRequestBytes() + ".");
    }

    private static void copyHeaders(Map<String, List<String>> source, HttpHeaders target) {
        source.forEach(target::put);
    }

    static final class DeliveryAwareServerResponse implements ServerResponse {
        private final ServerResponse delegate;

        private final HostingHttpResponse response;

        private final AtomicBoolean resolved = new AtomicBoolean();

        DeliveryAwareServerResponse(ServerResponse delegate, HostingHttpResponse response) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
            this.response = java.util.Objects.requireNonNull(response, "response");
        }

        @Override
        public HttpStatusCode statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public MultiValueMap<String, ResponseCookie> cookies() {
            return delegate.cookies();
        }

        @Override
        public Mono<Void> writeTo(ServerWebExchange exchange, Context context) {
            return Mono.defer(() -> delegate.writeTo(exchange, context))
                    .doOnSuccess(ignored -> confirm())
                    .doOnError(ignored -> discard())
                    .doOnCancel(this::discard);
        }

        private void confirm() {
            if (resolved.compareAndSet(false, true)) {
                response.confirmDelivery();
            }
        }

        private void discard() {
            if (!resolved.compareAndSet(false, true)) {
                return;
            }
            response.discardUndeliveredOutcome();
            HostingRun run = response.streamingRun();
            if (run != null) {
                run.cancel();
            }
        }
    }
}
