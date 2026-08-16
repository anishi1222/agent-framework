// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingApprovalRequest;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingContinuationDescriptor;
import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRun;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingHttpHandlerTest {
    @Test
    void sseEncodingAndDeliveryFailure_shouldDiscardContinuationExactlyOnceAcrossRace() {
        int attempts = 100;
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        HostingJsonCodec codec = mock(HostingJsonCodec.class);
        doThrow(new HostingException(HostingErrorCode.OVERFLOW, "encoded terminal is too large"))
                .when(codec)
                .encodeOutcome(any());
        try (HostingHttpHandler handler = new HostingHttpHandler(
                dispatcher, HostingHttpServerOptions.builder().build(), codec)) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                HostingOutcome outcome = approvalOutcome(attempt);
                CompletableFuture<HostingOutcome> terminal = new CompletableFuture<>();
                HostingRun run =
                        new HostingRun(outcome.runId(), subscriber -> {}, terminal, new DefaultRunCancellation());
                HostingHttpResponse response =
                        HostingHttpResponse.trackedSse(Map.of(), run, dispatcher::discardUndeliveredOutcome);
                terminal.complete(outcome);

                CompletableFuture.allOf(
                                CompletableFuture.runAsync(response::discardUndeliveredOutcome),
                                CompletableFuture.runAsync(
                                        () -> assertThatThrownBy(() -> handler.encodeOutcome(outcome, response))
                                                .isInstanceOf(HostingException.class)
                                                .extracting(failure -> ((HostingException) failure)
                                                        .error()
                                                        .code())
                                                .isEqualTo(HostingErrorCode.OVERFLOW)))
                        .join();
            }
        }

        verify(dispatcher, times(attempts)).discardUndeliveredOutcome(any());
    }

    @Test
    void webSocketAuthentication_shouldShareBoundedAdmissionAndReleaseOnTimeout() {
        AtomicInteger calls = new AtomicInteger();
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofMillis(50))
                .build();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> calls.getAndIncrement() == 0
                        ? new CompletableFuture<>()
                        : CompletableFuture.completedFuture(
                                HostingAuthentication.authenticated(new HostingPrincipal("owner", "tenant"))))
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(new HostingRegistry(), limits);
                HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options)) {

            CompletableFuture<?> first =
                    handler.authenticateWebSocketAsync(request()).toCompletableFuture();
            assertThatThrownBy(() -> handler.authenticateWebSocketAsync(request())
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.TOO_MANY_REQUESTS);
            assertThatThrownBy(() -> first.orTimeout(2, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.RUN_TIMEOUT);

            assertThat(handler.authenticateWebSocketAsync(request())
                            .toCompletableFuture()
                            .orTimeout(2, TimeUnit.SECONDS)
                            .join()
                            .principalId())
                    .isEqualTo("owner");
            assertThat(calls).hasValue(2);
            assertThat(handler.pendingAuthenticationCount()).isZero();
            assertThat(handler.scheduledAuthenticationTimeoutCount()).isZero();
            assertThat(handler.availableRequestPermits()).isEqualTo(1);
        }
    }

    @Test
    void httpErrors_shouldUseBoundedFallbackAndReleasePermitForEveryRequest() {
        HostingLimits limits = HostingLimits.builder()
                .maxResponseBytes(153)
                .maxConcurrentRequests(1)
                .build();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> CompletableFuture.completedFuture(authenticated()))
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(new HostingRegistry(), limits);
                HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options)) {
            for (int attempt = 0; attempt < 100; attempt++) {
                HostingHttpResponse response = handler.handleAsync(request("POST", "/v1", new DefaultRunCancellation()))
                        .toCompletableFuture()
                        .orTimeout(2, TimeUnit.SECONDS)
                        .join();

                assertThat(response.status()).isEqualTo(405);
                assertThat(response.body()).hasSizeLessThanOrEqualTo(153);
                assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains(
                                "\"type\":\"error\"",
                                "\"code\":\"method_not_allowed\"",
                                "\"message\":\"Request failed.\"");
                assertThat(handler.availableRequestPermits()).isEqualTo(1);
            }
        }
    }

    @Test
    void authentication_shouldRemoveCompletedTimeoutsUnderHighVolume() {
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(4)
                .idleTimeout(Duration.ofHours(1))
                .build();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> CompletableFuture.completedFuture(authenticated()))
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(new HostingRegistry(), limits);
                HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options)) {
            for (int attempt = 0; attempt < 1_000; attempt++) {
                assertThat(handler.authenticateWebSocketAsync(request())
                                .toCompletableFuture()
                                .orTimeout(2, TimeUnit.SECONDS)
                                .join()
                                .principalId())
                        .isEqualTo("owner");
            }

            assertThat(handler.pendingAuthenticationCount()).isZero();
            assertThat(handler.scheduledAuthenticationTimeoutCount()).isZero();
            assertThat(handler.availableRequestPermits()).isEqualTo(4);
        }
    }

    @Test
    void authentication_shouldReleaseTimersAcrossCompletionCancellationRaces() {
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofHours(1))
                .build();
        ConcurrentLinkedQueue<CompletableFuture<HostingAuthentication>> authentications = new ConcurrentLinkedQueue<>();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> authentications.remove())
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(new HostingRegistry(), limits);
                HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options)) {
            for (int attempt = 0; attempt < 100; attempt++) {
                CompletableFuture<HostingAuthentication> authentication = new CompletableFuture<>();
                authentications.add(authentication);
                DefaultRunCancellation cancellation = new DefaultRunCancellation();
                CompletableFuture<?> result = handler.authenticateWebSocketAsync(request("GET", "/v1/ws", cancellation))
                        .toCompletableFuture();

                CompletableFuture.allOf(
                                CompletableFuture.runAsync(cancellation::cancel),
                                CompletableFuture.runAsync(() -> authentication.complete(authenticated())))
                        .orTimeout(2, TimeUnit.SECONDS)
                        .join();
                try {
                    result.orTimeout(2, TimeUnit.SECONDS).join();
                } catch (RuntimeException ignored) {
                    // Cancellation is an equally valid winner.
                }

                assertThat(handler.pendingAuthenticationCount()).isZero();
                assertThat(handler.scheduledAuthenticationTimeoutCount()).isZero();
                assertThat(handler.availableRequestPermits()).isEqualTo(1);
            }
        }
    }

    @Test
    void authentication_shouldCancelPendingAttemptAndTimerOnHandlerClose() {
        HostingLimits limits = HostingLimits.builder()
                .maxConcurrentRequests(1)
                .idleTimeout(Duration.ofHours(1))
                .build();
        CompletableFuture<HostingAuthentication> authentication = new CompletableFuture<>();
        HostingHttpServerOptions options = HostingHttpServerOptions.builder()
                .limits(limits)
                .authenticator(request -> authentication)
                .build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(new HostingRegistry(), limits)) {
            HostingHttpHandler handler = new HostingHttpHandler(dispatcher, options);
            CompletableFuture<?> result =
                    handler.authenticateWebSocketAsync(request()).toCompletableFuture();
            assertThat(handler.pendingAuthenticationCount()).isEqualTo(1);
            assertThat(handler.scheduledAuthenticationTimeoutCount()).isEqualTo(1);

            handler.close();
            authentication.complete(authenticated());

            assertThatThrownBy(result::join)
                    .hasRootCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.CLIENT_CANCELLED);
            assertThat(handler.pendingAuthenticationCount()).isZero();
            assertThat(handler.scheduledAuthenticationTimeoutCount()).isZero();
            assertThat(handler.availableRequestPermits()).isEqualTo(1);
            assertThat(handler.isAuthenticationSchedulerShutdown()).isTrue();
        }
    }

    private static HostingHttpRequest request() {
        return request("GET", "/v1/ws", new DefaultRunCancellation());
    }

    private static HostingHttpRequest request(String method, String path, DefaultRunCancellation cancellation) {
        return new HostingHttpRequest(
                method,
                URI.create(path),
                new InetSocketAddress("127.0.0.1", 12345),
                Map.of(
                        "host",
                        List.of("localhost:8080"),
                        "sec-websocket-protocol",
                        List.of(HostingWebSocketProtocol.SUBPROTOCOL)),
                new byte[0],
                cancellation);
    }

    private static HostingAuthentication authenticated() {
        return HostingAuthentication.authenticated(new HostingPrincipal("owner", "tenant"));
    }

    private static HostingOutcome approvalOutcome(int attempt) {
        HostingApprovalRequest approval =
                new HostingApprovalRequest("approval-" + attempt, "write", StateValue.object(Map.of()));
        HostingContinuationDescriptor continuation = new HostingContinuationDescriptor(
                "continuation-" + attempt,
                HostingContinuationType.APPROVAL,
                Instant.parse("2030-01-01T00:00:00Z"),
                List.of(approval));
        return HostingOutcome.approvalRequired("run-" + attempt, continuation);
    }
}
