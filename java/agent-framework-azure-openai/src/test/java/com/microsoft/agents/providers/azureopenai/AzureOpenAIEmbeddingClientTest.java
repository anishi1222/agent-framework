// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingTransport;
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

class AzureOpenAIEmbeddingClientTest {
    @Test
    void generate_shouldBatchPreserveOrderMapAzureOptionsAndFoldUsage() {
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
                    "resolved-deployment",
                    UsageDetails.builder()
                            .inputTokens(request.values().size())
                            .totalTokens(request.values().size())
                            .build(),
                    Map.of("openai.requestId", StateValue.string("request-" + transport.requests.size()))));
        };
        AzureOpenAIEmbeddingClient client = client(transport, 2, false);
        AzureOpenAIEmbeddingOptions requestOptions = AzureOpenAIEmbeddingOptions.builder()
                .model("override-deployment")
                .dimensions(2)
                .user("user-1")
                .inputType("document")
                .metadata(Map.of("tenant", StateValue.string("contoso")))
                .build();

        // Act
        GeneratedEmbeddings<FloatEmbeddingVector, AzureOpenAIEmbeddingOptions> generated = client.generateAsync(
                        List.of("alpha", "beta", "gamma"), requestOptions)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).values()).containsExactly("alpha", "beta");
        assertThat(transport.requests.get(1).values()).containsExactly("gamma");
        assertThat(transport.requests).allSatisfy(request -> {
            assertThat(request.model()).isEqualTo("override-deployment");
            assertThat(request.dimensions()).isEqualTo(2);
            assertThat(request.user()).isEqualTo("user-1");
            assertThat(request.metadata())
                    .containsEntry(
                            AzureOpenAISdkEmbeddingTransport.INPUT_TYPE_METADATA_KEY, StateValue.string("document"))
                    .containsEntry("tenant", StateValue.string("contoso"));
        });
        assertThat(generated.options()).isSameAs(requestOptions);
        assertThat(generated.embeddings())
                .extracting(embedding -> embedding.vector().values().getFirst())
                .containsExactly(1.0, 2.0, 3.0);
        assertThat(generated.embeddings()).allSatisfy(embedding -> {
            assertThat(embedding.model()).isEqualTo("resolved-deployment");
            assertThat(embedding.dimensions()).isEqualTo(2);
        });
        assertThat(generated.usage().inputTokens()).contains(java.math.BigInteger.valueOf(3));
        assertThat(generated.metadata())
                .containsEntry("azureOpenai.batchCount", StateValue.integer(2))
                .containsEntry("azureOpenai.requestId", StateValue.string("request-2"))
                .doesNotContainKey(AzureOpenAISdkEmbeddingTransport.INPUT_TYPE_METADATA_KEY);
    }

    @Test
    void generate_shouldCancelActiveStageAndRespectTransportOwnership() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        CompletableFuture<OpenAIEmbeddingTransport.Response> pending = new CompletableFuture<>();
        transport.handler = (request, cancellation) -> pending;
        AzureOpenAIEmbeddingClient client = client(transport, 1, true);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, AzureOpenAIEmbeddingOptions>> stage =
                client.generateAsync(List.of("one", "two"), AzureOpenAIEmbeddingOptions.empty(), cancellation);
        cancellation.cancel();
        client.close();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(pending).isCancelled();
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.closed).isTrue();
    }

    @Test
    void options_shouldValidateAuthVersionsAndRedactSecrets() {
        assertThatThrownBy(() -> AzureOpenAIEmbeddingClientOptions.builder()
                        .endpoint("https://resource.openai.azure.com")
                        .deployment("deployment")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AzureOpenAIEmbeddingClientOptions.builder()
                        .endpoint("https://resource.openai.azure.com")
                        .deployment("deployment")
                        .apiVersion("2024-10-21")
                        .apiKey("secret")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(options(2).toString()).contains("[REDACTED]").doesNotContain("secret");
    }

    private static AzureOpenAIEmbeddingClient client(
            RecordingTransport transport, int batchSize, boolean closeTransport) {
        return AzureOpenAIEmbeddingClient.builder()
                .options(options(batchSize))
                .transport(transport, closeTransport)
                .build();
    }

    private static AzureOpenAIEmbeddingClientOptions options(int batchSize) {
        return AzureOpenAIEmbeddingClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("default-deployment")
                .apiKey("secret")
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
