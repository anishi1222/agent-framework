// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.core.Embedding;
import com.microsoft.agents.core.EmbeddingClient;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingClient;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingClientOptions;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingEncodingFormat;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingOptions;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingTransport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates text embeddings through Azure OpenAI.
 *
 * <p>The client reuses the OpenAI provider's tested batching, ordering, cancellation, dimension,
 * and lifecycle core while Azure authentication, service versions, request options, telemetry, and
 * failures remain isolated in this module.
 */
public final class AzureOpenAIEmbeddingClient
        implements EmbeddingClient<String, FloatEmbeddingVector, AzureOpenAIEmbeddingOptions> {
    private final AzureOpenAIEmbeddingClientOptions options;

    private final OpenAIEmbeddingClient delegate;

    private final AtomicBoolean closed = new AtomicBoolean();

    private AzureOpenAIEmbeddingClient(
            AzureOpenAIEmbeddingClientOptions options, OpenAIEmbeddingTransport transport, boolean ownsTransport) {
        this.options = options;
        OpenAIEmbeddingClientOptions delegateOptions = OpenAIEmbeddingClientOptions.builder()
                .model(options.deployment())
                .timeout(options.timeout())
                .maxRetries(options.maxRetries())
                .maxBatchSize(options.maxBatchSize())
                .build();
        delegate = OpenAIEmbeddingClient.builder()
                .options(delegateOptions)
                .transport(transport, ownsTransport)
                .build();
    }

    /**
     * Creates a client builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable client options.
     *
     * @return client options
     */
    public AzureOpenAIEmbeddingClientOptions options() {
        return options;
    }

    @Override
    public CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, AzureOpenAIEmbeddingOptions>> generateAsync(
            List<? extends String> values, AzureOpenAIEmbeddingOptions requestOptions, RunCancellation cancellation) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(cancellation, "cancellation");
        AzureOpenAIEmbeddingOptions effectiveOptions =
                requestOptions == null ? AzureOpenAIEmbeddingOptions.empty() : requestOptions;
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(effectiveOptions.metadata());
        if (effectiveOptions.inputType() != null) {
            metadata.put(
                    AzureOpenAISdkEmbeddingTransport.INPUT_TYPE_METADATA_KEY,
                    StateValue.string(effectiveOptions.inputType()));
        }
        OpenAIEmbeddingOptions mapped = new OpenAIEmbeddingOptions(
                effectiveOptions.model(),
                effectiveOptions.dimensions(),
                OpenAIEmbeddingEncodingFormat.FLOAT,
                effectiveOptions.user(),
                metadata);
        CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions>> stage;
        try {
            stage = delegate.generateAsync(values, mapped, cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(AzureOpenAIErrorMapper.map(failure));
        }
        CompletableFuture<GeneratedEmbeddings<FloatEmbeddingVector, AzureOpenAIEmbeddingOptions>> result =
                new CompletableFuture<>();
        stage.whenComplete((generated, failure) -> {
            if (failure != null) {
                result.completeExceptionally(AzureOpenAIErrorMapper.map(failure));
            } else if (generated == null) {
                result.completeExceptionally(
                        AzureOpenAIErrorMapper.map(new IllegalStateException("Azure OpenAI returned no embeddings.")));
            } else {
                result.complete(remap(generated, requestOptions));
            }
        });
        return result;
    }

    /** Cancels active work and closes an owned transport. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close();
        }
    }

    private static GeneratedEmbeddings<FloatEmbeddingVector, AzureOpenAIEmbeddingOptions> remap(
            GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions> generated,
            AzureOpenAIEmbeddingOptions options) {
        List<Embedding<FloatEmbeddingVector>> embeddings = generated.embeddings().stream()
                .map(embedding -> new Embedding<>(
                        embedding.vector(),
                        embedding.model(),
                        embedding.createdAt(),
                        remapMetadata(embedding.metadata())))
                .toList();
        return new GeneratedEmbeddings<>(embeddings, options, generated.usage(), remapMetadata(generated.metadata()));
    }

    private static Map<String, StateValue> remapMetadata(Map<String, StateValue> source) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key.equals(AzureOpenAISdkEmbeddingTransport.INPUT_TYPE_METADATA_KEY)) {
                return;
            }
            result.put(key.startsWith("openai.") ? "azureOpenai." + key.substring("openai.".length()) : key, value);
        });
        return Map.copyOf(result);
    }

    /** Builds immutable {@link AzureOpenAIEmbeddingClient} instances. */
    public static final class Builder {
        private AzureOpenAIEmbeddingClientOptions options;

        private OpenAIEmbeddingTransport transport;

        private boolean closeTransport;

        private Builder() {}

        /** Sets required immutable client options. */
        public Builder options(AzureOpenAIEmbeddingClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Injects a caller-owned deterministic transport boundary. */
        public Builder transport(OpenAIEmbeddingTransport transport) {
            return transport(transport, false);
        }

        /** Injects a transport and selects whether ownership transfers to the client. */
        public Builder transport(OpenAIEmbeddingTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /** Creates a configured embedding client. */
        public AzureOpenAIEmbeddingClient build() {
            AzureOpenAIEmbeddingClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport == null) {
                return new AzureOpenAIEmbeddingClient(
                        builtOptions, AzureOpenAISdkEmbeddingTransport.create(builtOptions), true);
            }
            return new AzureOpenAIEmbeddingClient(builtOptions, transport, closeTransport);
        }
    }
}
