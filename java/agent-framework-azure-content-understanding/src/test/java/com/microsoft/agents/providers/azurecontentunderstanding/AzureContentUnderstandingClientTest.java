// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class AzureContentUnderstandingClientTest {
    @Test
    void bytesInput_shouldExposeInternalLengthWithoutWeakeningPublicDefensiveCopy() {
        ContentBytesInput input = new ContentBytesInput(new byte[] {1, 2, 3}, "doc.pdf", "application/pdf", null);

        byte[] publicCopy = input.bytes();
        publicCopy[0] = 9;

        assertThat(input.byteLength()).isEqualTo(3);
        assertThat(input.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void urlInput_shouldRedactSasAndRejectLocalTargets() {
        ContentUrlInput input = new ContentUrlInput(
                URI.create("https://storage.example.com/doc.pdf?sig=credential-secret"),
                "doc.pdf",
                "application/pdf",
                "1-3");

        assertThat(input.toString()).contains("?[REDACTED]").doesNotContain("credential-secret");
        assertThatThrownBy(() ->
                        new ContentUrlInput(URI.create("https://127.0.0.1/doc.pdf"), null, "application/pdf", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void client_shouldEnforceAggregateByteLimitBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        AzureContentUnderstandingOptions options = options().maxInputBytes(2).build();
        try (AzureContentUnderstandingClient client = new AzureContentUnderstandingClient(options, transport)) {
            ContentAnalysisRequest request = new ContentAnalysisRequest(
                    "prebuilt-layout",
                    List.of(new ContentBytesInput(new byte[] {1, 2, 3}, "doc.pdf", "application/pdf", null)),
                    Map.of());

            assertThatThrownBy(() -> client.startAnalysis(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxInputBytes");
            assertThat(transport.analysisCalls).hasValue(0);
        }
    }

    @Test
    void runHandleCancellation_shouldCancelLogicalResultWithoutRemoteDelete() {
        FakeTransport transport = new FakeTransport();
        transport.pending = new CompletableFuture<>();
        try (AzureContentUnderstandingClient client =
                new AzureContentUnderstandingClient(options().build(), transport)) {
            RunHandle<ContentAnalysisResult> handle = client.startAnalysis(new ContentAnalysisRequest(
                    "prebuilt-layout",
                    List.of(new ContentUrlInput(
                            URI.create("https://storage.example.com/doc.pdf"), null, "application/pdf", null)),
                    Map.of()));

            handle.cancel();

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(transport.deleteCalls).hasValue(0);
        }
    }

    @Test
    void synchronousTransportFailure_shouldReturnSettledHandleAndNotLeakActiveRun() {
        FakeTransport transport = new FakeTransport();
        transport.throwSynchronously = true;
        try (AzureContentUnderstandingClient client =
                new AzureContentUnderstandingClient(options().build(), transport)) {
            RunHandle<ContentAnalysisResult> handle = client.startAnalysis(new ContentAnalysisRequest(
                    "prebuilt-layout",
                    List.of(new ContentBytesInput(new byte[] {1}, null, "application/pdf", null)),
                    Map.of()));

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("synchronous transport failure");
        }
    }

    private static AzureContentUnderstandingOptions.Builder options() {
        return AzureContentUnderstandingOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .operationTimeout(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(1));
    }

    private static final class FakeTransport implements ContentUnderstandingTransport {
        private final java.util.concurrent.atomic.AtomicInteger analysisCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger deleteCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private boolean throwSynchronously;
        private CompletableFuture<ContentAnalysisResult> pending;

        @Override
        public CompletionStage<ContentAnalysisResult> analyzeAsync(
                ContentAnalysisRequest request, RunCancellation cancellation) {
            analysisCalls.incrementAndGet();
            if (throwSynchronously) {
                throw new IllegalStateException("synchronous transport failure");
            }
            return pending == null
                    ? CompletableFuture.completedStage(new ContentAnalysisResult(
                            "operation-one",
                            ContentOperationStatus.SUCCEEDED,
                            request.analyzerId(),
                            "2025-11-01",
                            Instant.now(),
                            "codePoint",
                            List.of(),
                            List.of(),
                            Map.of()))
                    : pending;
        }

        @Override
        public CompletionStage<ContentAnalyzerDefinition> createAnalyzerAsync(
                ContentAnalyzerRequest request, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<ContentAnalyzerDefinition> getAnalyzerAsync(
                String analyzerId, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<ContentAnalyzerDefinition> updateAnalyzerAsync(
                ContentAnalyzerRequest request, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Void> deleteAnalyzerAsync(String analyzerId, RunCancellation cancellation) {
            deleteCalls.incrementAndGet();
            return CompletableFuture.completedStage(null);
        }

        @Override
        public CompletionStage<ContentUnderstandingPage<ContentAnalyzerDefinition>> listAnalyzersAsync(
                int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new ContentUnderstandingPage<>(List.of(), null, false));
        }
    }
}
