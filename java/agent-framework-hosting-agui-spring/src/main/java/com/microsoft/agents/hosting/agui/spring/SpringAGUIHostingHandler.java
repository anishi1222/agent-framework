// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui.spring;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.agui.AGUIHostedRun;
import com.microsoft.agents.hosting.agui.AGUIHostingHttpHandler;
import com.microsoft.agents.hosting.agui.AGUIHttpResponse;
import com.microsoft.agents.hosting.agui.AGUIPrincipalResolver;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

final class SpringAGUIHostingHandler {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final AGUIHostingHttpHandler handler;

    private final AGUIPrincipalResolver principalResolver;

    SpringAGUIHostingHandler(AGUIHostingHttpHandler handler, AGUIPrincipalResolver principalResolver) {
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
        this.principalResolver = java.util.Objects.requireNonNull(principalResolver, "principalResolver");
    }

    Mono<ServerResponse> handle(ServerRequest request) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        return readBody(request)
                .map(body -> toHostingRequest(request, body, cancellation))
                .flatMap(hostingRequest -> {
                    if ("OPTIONS".equals(hostingRequest.method())) {
                        return Mono.fromCompletionStage(handler.handleAsync(hostingRequest));
                    }
                    return request.principal()
                            .map(Principal::getName)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(name -> Mono.fromCompletionStage(
                                    handler.handleResolvedAsync(hostingRequest, name.orElse(null), principalResolver)));
                })
                .flatMap(this::toServerResponse)
                .onErrorResume(this::errorResponse)
                .doOnCancel(cancellation::cancel);
    }

    private Mono<byte[]> readBody(ServerRequest request) {
        long maximum = handler.transportOptions().limits().maxRequestBytes();
        long contentLength = request.headers().contentLength().orElse(-1L);
        if (contentLength > maximum) {
            return Mono.error(payloadTooLarge());
        }
        return DataBufferUtils.join(
                        request.bodyToFlux(DataBuffer.class), (int) Math.min(Integer.MAX_VALUE - 1L, maximum))
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
                        HostingErrorCode.MALFORMED_REQUEST, "Request peer address is unavailable."));
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        request.headers().asHttpHeaders().forEach(headers::put);
        String path = request.requestPath().pathWithinApplication().value();
        String query = request.uri().getRawQuery();
        URI uri = URI.create(path + (query == null ? "" : "?" + query));
        return new HostingHttpRequest(request.method().name(), uri, remote, headers, body, cancellation);
    }

    private Mono<ServerResponse> toServerResponse(AGUIHttpResponse response) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(response.status());
        builder.headers(headers -> response.headers().forEach(headers::put));
        Mono<ServerResponse> result = response.isStreaming()
                ? builder.body(sse(response.streamingRun()), SSE_TYPE)
                : builder.body(BodyInserters.fromValue(response.body()));
        return result.map(delegate -> (ServerResponse) new DeliveryAwareResponse(delegate, response));
    }

    private Flux<ServerSentEvent<String>> sse(AGUIHostedRun run) {
        return JdkFlowAdapter.flowPublisherToFlux(run.events())
                .map(event -> ServerSentEvent.<String>builder()
                        .data(new String(handler.codec().encodeEvent(event), StandardCharsets.UTF_8))
                        .build())
                .timeout(handler.transportOptions().limits().idleTimeout())
                .doOnCancel(run::discardUndelivered)
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL || signal == SignalType.ON_ERROR) {
                        run.discardUndelivered();
                    }
                });
    }

    private Mono<ServerResponse> errorResponse(Throwable failure) {
        Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
        int status = cause instanceof HostingException hosting
                ? hosting.error().code().httpStatus()
                : 500;
        String code = cause instanceof HostingException hosting
                ? hosting.error().code().value()
                : "internal_error";
        byte[] body = handler.codec()
                .encodeValue(com.microsoft.agents.core.StateValue.object(Map.of(
                        "error",
                        com.microsoft.agents.core.StateValue.object(Map.of(
                                "code",
                                com.microsoft.agents.core.StateValue.string(code),
                                "message",
                                com.microsoft.agents.core.StateValue.string("Request failed."))))));
        return ServerResponse.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BodyInserters.fromValue(body));
    }

    private HostingException payloadTooLarge() {
        return new HostingException(HostingErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds maxRequestBytes.");
    }

    private static final class DeliveryAwareResponse implements ServerResponse {
        private final ServerResponse delegate;

        private final AGUIHttpResponse response;

        private final AtomicBoolean resolved = new AtomicBoolean();

        private DeliveryAwareResponse(ServerResponse delegate, AGUIHttpResponse response) {
            this.delegate = delegate;
            this.response = response;
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
            if (resolved.compareAndSet(false, true) && response.streamingRun() != null) {
                response.streamingRun().confirmDelivery();
            }
        }

        private void discard() {
            if (resolved.compareAndSet(false, true) && response.streamingRun() != null) {
                response.streamingRun().discardUndelivered();
            }
        }
    }
}
