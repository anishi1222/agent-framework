// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class EmbeddingContractsTest {
    @Test
    void models_shouldDefensivelyCopyAndValidateFiniteDimensions() {
        // Arrange
        ArrayList<Double> values = new ArrayList<>(List.of(0.1, 0.2));
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(Map.of("scope", StateValue.string("test")));

        // Act
        FloatEmbeddingVector vector = new FloatEmbeddingVector(values);
        Embedding<FloatEmbeddingVector> embedding = new Embedding<>(vector, "model", null, metadata);
        GeneratedEmbeddings<FloatEmbeddingVector, EmbeddingGenerationOptions> generated = new GeneratedEmbeddings<>(
                List.of(embedding),
                EmbeddingGenerationOptions.builder().dimensions(2).build(),
                UsageDetails.of(UsageDetails.INPUT_TOKENS, 1),
                metadata);
        values.set(0, 9.0);
        metadata.clear();

        // Assert
        assertThat(vector.values()).containsExactly(0.1, 0.2);
        assertThat(embedding.dimensions()).isEqualTo(2);
        assertThat(embedding.metadata()).containsKey("scope");
        assertThat(generated).containsExactly(embedding);
        assertThat(generated.metadata()).containsKey("scope");
        assertThatThrownBy(() -> vector.values().add(0.3)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new FloatEmbeddingVector(List.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new FloatEmbeddingVector(List.of(Double.NaN))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(
                        () -> EmbeddingGenerationOptions.builder().dimensions(0).build())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void clientDefaults_shouldSupplyCancellationAndPreserveOptions() {
        // Arrange
        EmbeddingGenerationOptions options = EmbeddingGenerationOptions.builder()
                .model("model")
                .dimensions(1)
                .build();
        EmbeddingClient<String, FloatEmbeddingVector, EmbeddingGenerationOptions> client =
                (values, suppliedOptions, cancellation) -> {
                    assertThat(cancellation.isCancellationRequested()).isFalse();
                    return CompletableFuture.completedFuture(new GeneratedEmbeddings<>(
                            values.stream()
                                    .map(ignored -> new Embedding<>(new FloatEmbeddingVector(List.of(1.0))))
                                    .toList(),
                            suppliedOptions,
                            null));
                };

        // Act
        GeneratedEmbeddings<FloatEmbeddingVector, EmbeddingGenerationOptions> generated = client.generateAsync(
                        List.of("one", "two"), options)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(generated).hasSize(2);
        assertThat(generated.options()).isSameAs(options);
    }

    @Test
    void clientCancellation_shouldRemainExplicitAndTyped() {
        // Arrange
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        cancellation.cancel();
        EmbeddingClient<String, FloatEmbeddingVector, EmbeddingGenerationOptions> client =
                (values, options, signal) -> signal.isCancellationRequested()
                        ? CompletableFuture.failedFuture(new RunCancelledException())
                        : CompletableFuture.completedFuture(new GeneratedEmbeddings<>(List.of(), options, null));

        // Act / Assert
        assertThatThrownBy(
                        () -> client.generateAsync(List.of("value"), EmbeddingGenerationOptions.empty(), cancellation)
                                .toCompletableFuture()
                                .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
    }
}
