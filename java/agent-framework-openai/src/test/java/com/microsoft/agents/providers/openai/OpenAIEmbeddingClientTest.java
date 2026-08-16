// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class OpenAIEmbeddingClientTest {
    @Test
    void generate_shouldBatchPreserveInputOrderAndFoldUsage() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        transport.handler = (request, cancellation) -> {
            ArrayList<OpenAIEmbeddingTransport.Item> items = new ArrayList<>();
            for (int index = request.values().size() - 1; index >= 0; index--) {
                double marker =
                        switch (request.values().get(index)) {
                            case "alpha" -> 1.0;
                            case "beta" -> 2.0;
                            default -> 3.0;
                        };
                items.add(new OpenAIEmbeddingTransport.Item(
                        index, new FloatEmbeddingVector(List.of(marker, marker + 0.5))));
            }
            return CompletableFuture.completedFuture(new OpenAIEmbeddingTransport.Response(
                    items,
                    "resolved-model",
                    UsageDetails.builder()
                            .inputTokens(request.values().size())
                            .totalTokens(request.values().size())
                            .build(),
                    Map.of("openai.requestId", StateValue.string("request-" + transport.requests.size()))));
        };
        OpenAIEmbeddingClient client = client(transport, 2);
        OpenAIEmbeddingOptions requestOptions = OpenAIEmbeddingOptions.builder()
                .dimensions(2)
                .encodingFormat(OpenAIEmbeddingEncodingFormat.BASE64)
                .user("user-1")
                .build();

        // Act
        GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions> generated = client.generateAsync(
                        List.of("alpha", "beta", "gamma"), requestOptions)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).values()).containsExactly("alpha", "beta");
        assertThat(transport.requests.get(1).values()).containsExactly("gamma");
        assertThat(transport.requests).allSatisfy(request -> {
            assertThat(request.dimensions()).isEqualTo(2);
            assertThat(request.encodingFormat()).isEqualTo(OpenAIEmbeddingEncodingFormat.BASE64);
            assertThat(request.user()).isEqualTo("user-1");
        });
        assertThat(generated.options()).isSameAs(requestOptions);
        assertThat(generated.embeddings())
                .extracting(embedding -> embedding.vector().values().getFirst())
                .containsExactly(1.0, 2.0, 3.0);
        assertThat(generated.embeddings()).allSatisfy(embedding -> {
            assertThat(embedding.model()).isEqualTo("resolved-model");
            assertThat(embedding.dimensions()).isEqualTo(2);
        });
        assertThat(generated.usage().inputTokens()).contains(java.math.BigInteger.valueOf(3));
        assertThat(generated.metadata())
                .containsEntry("openai.batchCount", StateValue.integer(2))
                .containsEntry("openai.requestId", StateValue.string("request-2"));
    }

    @Test
    void generate_shouldShortCircuitEmptyAndRejectProtocolDimensionMismatch() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        OpenAIEmbeddingClient client = client(transport, 4);

        // Act
        GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions> empty = client.generateAsync(
                        List.of(), OpenAIEmbeddingOptions.empty())
                .toCompletableFuture()
                .join();
        transport.handler = (request, cancellation) ->
                CompletableFuture.completedFuture(new OpenAIEmbeddingTransport.Response(
                        List.of(new OpenAIEmbeddingTransport.Item(0, new FloatEmbeddingVector(List.of(1.0, 2.0)))),
                        request.model(),
                        null,
                        Map.of()));

        // Assert
        assertThat(empty).isEmpty();
        assertThat(transport.requests).isEmpty();
        assertThatThrownBy(() -> client.generateAsync(
                                List.of("value"),
                                OpenAIEmbeddingOptions.builder().dimensions(1).build())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        OpenAIProtocolException.class,
                        failure -> assertThat(failure.errorCode()).contains("embedding_dimension_mismatch"));
    }

    @Test
    void generate_shouldCancelActiveStageAndPreventLaterBatches() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        CompletableFuture<OpenAIEmbeddingTransport.Response> pending = new CompletableFuture<>();
        transport.handler = (request, cancellation) -> pending;
        OpenAIEmbeddingClient client = client(transport, 1);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions>> stage =
                client.generateAsync(List.of("one", "two"), OpenAIEmbeddingOptions.empty(), cancellation);
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(pending).isCancelled();
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void close_shouldRespectInjectedTransportOwnershipAndRejectNewWork() {
        // Arrange
        RecordingTransport callerOwned = new RecordingTransport();
        RecordingTransport transferred = new RecordingTransport();
        OpenAIEmbeddingClient first = OpenAIEmbeddingClient.builder()
                .options(options(2))
                .transport(callerOwned)
                .build();
        OpenAIEmbeddingClient second = OpenAIEmbeddingClient.builder()
                .options(options(2))
                .transport(transferred, true)
                .build();

        // Act
        first.close();
        second.close();

        // Assert
        assertThat(callerOwned.closed).isFalse();
        assertThat(transferred.closed).isTrue();
        assertThatThrownBy(() -> first.generateAsync(List.of("late"))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OpenAISdkException.class);
    }

    @Test
    void options_shouldValidateBoundsAndRedactSecrets() {
        assertThatThrownBy(() -> options(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenAIEmbeddingOptions.builder().dimensions(0).build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
        assertThat(OpenAIEmbeddingClientOptions.builder()
                        .apiKey("secret")
                        .model("model")
                        .build()
                        .toString())
                .contains("[REDACTED]")
                .doesNotContain("secret");
    }

    private static OpenAIEmbeddingClient client(RecordingTransport transport, int batchSize) {
        return OpenAIEmbeddingClient.builder()
                .options(options(batchSize))
                .transport(transport)
                .build();
    }

    private static OpenAIEmbeddingClientOptions options(int batchSize) {
        return OpenAIEmbeddingClientOptions.builder()
                .model("default-model")
                .maxBatchSize(batchSize)
                .build();
    }

    private static final class RecordingTransport implements OpenAIEmbeddingTransport {
        private final List<OpenAIEmbeddingTransport.Request> requests = new CopyOnWriteArrayList<>();

        private BiFunction<
                        OpenAIEmbeddingTransport.Request,
                        RunCancellation,
                        CompletionStage<OpenAIEmbeddingTransport.Response>>
                handler = (request, cancellation) -> CompletableFuture.completedFuture(
                new OpenAIEmbeddingTransport.Response(
                        request.values().stream()
                                .map(value -> new OpenAIEmbeddingTransport.Item(
                                        request.values().indexOf(value), new FloatEmbeddingVector(List.of(1.0))))
                                .toList(),
                        request.model(),
                        null,
                        Map.of()));

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public CompletionStage<OpenAIEmbeddingTransport.Response> generateAsync(
                OpenAIEmbeddingTransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            return handler.apply(request, cancellation);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
