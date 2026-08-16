// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Embedding;
import com.microsoft.agents.core.EmbeddingClient;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmbeddingProvidersTest {
    @Test
    void fromClient_shouldMapScopedRequestOptionsCancellationAndVector() {
        // Arrange
        AtomicReference<List<String>> values = new AtomicReference<>();
        AtomicReference<String> options = new AtomicReference<>();
        AtomicReference<RunCancellation> cancellation = new AtomicReference<>();
        EmbeddingClient<String, FloatEmbeddingVector, String> client =
                (input, suppliedOptions, suppliedCancellation) -> {
                    values.set(List.copyOf(input));
                    options.set(suppliedOptions);
                    cancellation.set(suppliedCancellation);
                    return CompletableFuture.completedStage(new GeneratedEmbeddings<>(
                            List.of(new Embedding<>(
                                    new FloatEmbeddingVector(List.of(0.25, 0.75)), "model", null, Map.of())),
                            suppliedOptions,
                            null,
                            Map.of()));
                };
        EmbeddingProvider provider = EmbeddingProviders.fromClientWithOptions(
                client,
                request -> request.scope().tenantId() + ":" + request.scope().scopeId());
        DefaultRunCancellation runCancellation = new DefaultRunCancellation();

        // Act
        EmbeddingVector vector = provider.generateAsync(
                        new EmbeddingRequest(new MemoryScope("tenant", "user"), "remember this"), runCancellation)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(values.get()).containsExactly("remember this");
        assertThat(options.get()).isEqualTo("tenant:user");
        assertThat(cancellation.get()).isSameAs(runCancellation);
        assertThat(vector.values()).containsExactly(0.25, 0.75);
    }

    @Test
    void fromClient_shouldRejectMissingOrMultipleResults() {
        // Arrange
        EmbeddingClient<String, FloatEmbeddingVector, Void> client = (input, options, cancellation) ->
                CompletableFuture.completedStage(new GeneratedEmbeddings<>(List.of(), options, null, Map.of()));
        EmbeddingProvider provider = EmbeddingProviders.fromClient(client);

        // Act and assert
        assertThatThrownBy(() -> provider.generateAsync(
                                new EmbeddingRequest(new MemoryScope("tenant", "user"), "text"),
                                new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AgentExecutionException.class);
    }
}
