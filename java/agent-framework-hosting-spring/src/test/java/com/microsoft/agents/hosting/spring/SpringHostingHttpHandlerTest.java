// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingApprovalRequest;
import com.microsoft.agents.hosting.HostingContinuationDescriptor;
import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpResponse;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SpringHostingHttpHandlerTest {
    private static final int DISCONNECT_ATTEMPTS = 100;

    @Test
    void reactiveWriteFailure_shouldDiscardSseTerminalOutcomeExactlyOnceAcrossRepeatedDisconnects() {
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        AtomicInteger runSequence = new AtomicInteger();
        when(dispatcher.startStreamingAsync(any(), eq(HostingRouteKind.AGENT), eq("spring-agent"), any()))
                .thenAnswer(ignored -> {
                    int sequence = runSequence.getAndIncrement();
                    return CompletableFuture.completedFuture(run(sequence));
                });
        HostingLimits limits = HostingLimits.defaults();
        try (HostingHttpHandler handler = new HostingHttpHandler(
                dispatcher, HostingHttpServerOptions.builder().limits(limits).build())) {
            for (int attempt = 0; attempt < DISCONNECT_ATTEMPTS; attempt++) {
                HostingHttpResponse response = handler.handleAsync(streamRequest())
                        .toCompletableFuture()
                        .join();
                ServerResponse delegate = mock(ServerResponse.class);
                when(delegate.writeTo(any(), any())).thenReturn(Mono.error(new IOException("peer disconnected")));
                SpringHostingHttpHandler.DeliveryAwareServerResponse delivery =
                        new SpringHostingHttpHandler.DeliveryAwareServerResponse(delegate, response);

                StepVerifier.create(delivery.writeTo(mock(ServerWebExchange.class), mock(ServerResponse.Context.class)))
                        .expectError(IOException.class)
                        .verify();
                response.discardUndeliveredOutcome();
            }
        }

        assertThat(runSequence).hasValue(DISCONNECT_ATTEMPTS);
        verify(dispatcher, times(DISCONNECT_ATTEMPTS)).discardUndeliveredOutcome(any());
    }

    @Test
    void reactiveWriteSuccess_shouldNotDiscardConfirmedTerminalOutcome() {
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        when(dispatcher.startStreamingAsync(any(), eq(HostingRouteKind.AGENT), eq("spring-agent"), any()))
                .thenReturn(CompletableFuture.completedFuture(run(1)));
        HostingLimits limits = HostingLimits.defaults();
        try (HostingHttpHandler handler = new HostingHttpHandler(
                dispatcher, HostingHttpServerOptions.builder().limits(limits).build())) {
            HostingHttpResponse response =
                    handler.handleAsync(streamRequest()).toCompletableFuture().join();
            ServerResponse delegate = mock(ServerResponse.class);
            when(delegate.writeTo(any(), any())).thenReturn(Mono.empty());
            SpringHostingHttpHandler.DeliveryAwareServerResponse delivery =
                    new SpringHostingHttpHandler.DeliveryAwareServerResponse(delegate, response);

            StepVerifier.create(delivery.writeTo(mock(ServerWebExchange.class), mock(ServerResponse.Context.class)))
                    .verifyComplete();
            response.discardUndeliveredOutcome();
        }

        verify(dispatcher, times(0)).discardUndeliveredOutcome(any());
    }

    private static HostingRun run(int sequence) {
        return new HostingRun(
                "run-" + sequence,
                completingPublisher(),
                CompletableFuture.completedFuture(approvalOutcome(sequence)),
                new DefaultRunCancellation());
    }

    private static HostingOutcome approvalOutcome(int sequence) {
        HostingApprovalRequest approval =
                new HostingApprovalRequest("approval-" + sequence, "write", StateValue.object(Map.of()));
        HostingContinuationDescriptor continuation = new HostingContinuationDescriptor(
                "continuation-" + sequence,
                HostingContinuationType.APPROVAL,
                Instant.parse("2030-01-01T00:00:00Z"),
                List.of(approval));
        return HostingOutcome.approvalRequired("run-" + sequence, continuation);
    }

    private static HostingHttpRequest streamRequest() {
        String body = """
                {
                  "version":"java-hosting-2026-08-01",
                  "messages":[
                    {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                  ]
                }
                """;
        return new HostingHttpRequest(
                "POST",
                URI.create("/v1/agents/spring-agent/runs/stream"),
                new InetSocketAddress("127.0.0.1", 12345),
                Map.of(
                        "host",
                        List.of("localhost:8080"),
                        "content-type",
                        List.of("application/json"),
                        "accept",
                        List.of("text/event-stream")),
                body.getBytes(StandardCharsets.UTF_8),
                new DefaultRunCancellation());
    }

    private static Flow.Publisher<com.microsoft.agents.hosting.HostingEvent> completingPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean complete = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (count > 0 && complete.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                complete.set(true);
            }
        });
    }
}
