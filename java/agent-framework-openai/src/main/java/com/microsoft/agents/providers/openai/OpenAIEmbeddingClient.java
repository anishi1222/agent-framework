// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.Embedding;
import com.microsoft.agents.core.EmbeddingClient;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates text embeddings through the OpenAI embeddings API.
 *
 * <p>Input order is preserved even when the provider returns indexed items out of order. Requests
 * larger than the configured batch size are issued sequentially, usage is folded across batches,
 * and cancellation stops the active request before any later batch starts.
 */
public final class OpenAIEmbeddingClient
        implements EmbeddingClient<String, FloatEmbeddingVector, OpenAIEmbeddingOptions> {
    private final OpenAIEmbeddingClientOptions options;

    private final OpenAIEmbeddingTransport transport;

    private final boolean ownsTransport;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<RunCancellation> activeCancellations = ConcurrentHashMap.newKeySet();

    private OpenAIEmbeddingClient(
            OpenAIEmbeddingClientOptions options, OpenAIEmbeddingTransport transport, boolean ownsTransport) {
        this.options = options;
        this.transport = transport;
        this.ownsTransport = ownsTransport;
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
    public OpenAIEmbeddingClientOptions options() {
        return options;
    }

    @Override
    public CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions>> generateAsync(
            List<? extends String> values, OpenAIEmbeddingOptions requestOptions, RunCancellation cancellation) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(cancellation, "cancellation");
        if (closed.get()) {
            return CompletableFuture.failedFuture(closedFailure());
        }
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }

        List<String> copiedValues;
        try {
            copiedValues = List.copyOf(values);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (copiedValues.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new GeneratedEmbeddings<>(List.of(), requestOptions, null, Map.of()));
        }

        OpenAIEmbeddingOptions effectiveOptions =
                requestOptions == null ? OpenAIEmbeddingOptions.empty() : requestOptions;
        String model = effectiveOptions.model() == null ? options.model() : effectiveOptions.model();
        OpenAIEmbeddingEncodingFormat encoding = effectiveOptions.encodingFormat() == null
                ? OpenAIEmbeddingEncodingFormat.FLOAT
                : effectiveOptions.encodingFormat();
        Run run = new Run(copiedValues, requestOptions, effectiveOptions, model, encoding, cancellation);
        activeCancellations.add(cancellation);
        if (closed.get()) {
            activeCancellations.remove(cancellation);
            cancellation.cancel();
            return CompletableFuture.failedFuture(closedFailure());
        }
        run.start();
        return run.result;
    }

    /**
     * Cancels active work and closes an owned transport.
     *
     * <p>An injected transport is caller-owned unless ownership was explicitly transferred through
     * {@link Builder#transport(OpenAIEmbeddingTransport, boolean)}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeCancellations.forEach(RunCancellation::cancel);
        activeCancellations.clear();
        if (ownsTransport) {
            try {
                transport.close();
            } catch (RuntimeException failure) {
                throw normalizeFailure(failure);
            }
        }
    }

    private static void cancelStage(CompletionStage<?> stage) {
        if (stage == null) {
            return;
        }
        try {
            stage.toCompletableFuture().cancel(true);
        } catch (RuntimeException ignored) {
            // The explicit cancellation signal remains authoritative.
        }
    }

    private static void closeRegistration(RunCancellationRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static RuntimeException normalizeFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            if (runtime instanceof AgentFrameworkException) {
                return runtime;
            }
            return OpenAIErrorMapper.map(runtime);
        }
        return new OpenAISdkException("transport_error");
    }

    private static RuntimeException closedFailure() {
        return new OpenAISdkException("client_closed");
    }

    /** Builds immutable {@link OpenAIEmbeddingClient} instances. */
    public static final class Builder {
        private OpenAIEmbeddingClientOptions options;

        private OpenAIEmbeddingTransport transport;

        private boolean closeTransport;

        private Builder() {}

        /**
         * Sets required immutable client options.
         *
         * @param options client options
         * @return this builder
         */
        public Builder options(OpenAIEmbeddingClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Injects a caller-owned transport.
         *
         * @param transport transport boundary
         * @return this builder
         */
        public Builder transport(OpenAIEmbeddingTransport transport) {
            return transport(transport, false);
        }

        /**
         * Injects a transport and selects whether ownership transfers to the client.
         *
         * @param transport transport boundary
         * @param closeTransport whether the client closes the transport
         * @return this builder
         */
        public Builder transport(OpenAIEmbeddingTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Creates a configured client.
         *
         * @return embedding client
         */
        public OpenAIEmbeddingClient build() {
            OpenAIEmbeddingClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport == null) {
                return new OpenAIEmbeddingClient(builtOptions, OpenAISdkEmbeddingTransport.create(builtOptions), true);
            }
            return new OpenAIEmbeddingClient(builtOptions, transport, closeTransport);
        }
    }

    private final class Run {
        private final List<String> values;

        private final OpenAIEmbeddingOptions returnedOptions;

        private final OpenAIEmbeddingOptions effectiveOptions;

        private final String model;

        private final OpenAIEmbeddingEncodingFormat encoding;

        private final RunCancellation cancellation;

        private final CompletableFuture<GeneratedEmbeddings<FloatEmbeddingVector, OpenAIEmbeddingOptions>> result =
                new CompletableFuture<>();

        private final AtomicReference<CompletionStage<?>> currentStage = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();

        private final ArrayList<Embedding<FloatEmbeddingVector>> embeddings = new ArrayList<>();

        private final LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();

        private UsageDetails usage;

        private Integer observedDimensions;

        private int batchCount;

        private Run(
                List<String> values,
                OpenAIEmbeddingOptions returnedOptions,
                OpenAIEmbeddingOptions effectiveOptions,
                String model,
                OpenAIEmbeddingEncodingFormat encoding,
                RunCancellation cancellation) {
            this.values = values;
            this.returnedOptions = returnedOptions;
            this.effectiveOptions = effectiveOptions;
            this.model = model;
            this.encoding = encoding;
            this.cancellation = cancellation;
        }

        private void start() {
            registration.set(RunCancellations.register(cancellation, () -> {
                cancelStage(currentStage.get());
                result.completeExceptionally(new RunCancelledException());
            }));
            result.whenComplete((ignored, failure) -> {
                activeCancellations.remove(cancellation);
                closeRegistration(registration.getAndSet(null));
            });
            startBatch(0);
        }

        private void startBatch(int offset) {
            if (result.isDone()) {
                return;
            }
            if (closed.get()) {
                result.completeExceptionally(closedFailure());
                return;
            }
            if (cancellation.isCancellationRequested()) {
                result.completeExceptionally(new RunCancelledException());
                return;
            }
            if (offset == values.size()) {
                metadata.put("openai.batchCount", StateValue.integer(batchCount));
                result.complete(new GeneratedEmbeddings<>(List.copyOf(embeddings), returnedOptions, usage, metadata));
                return;
            }

            int end = Math.min(offset + options.maxBatchSize(), values.size());
            OpenAIEmbeddingTransport.Request request = new OpenAIEmbeddingTransport.Request(
                    values.subList(offset, end),
                    model,
                    effectiveOptions.dimensions(),
                    encoding,
                    effectiveOptions.user(),
                    effectiveOptions.metadata());
            CompletionStage<OpenAIEmbeddingTransport.Response> stage;
            try {
                FeatureUsageIndexes.markOpenAiUsed();
                stage = Objects.requireNonNull(
                        transport.generateAsync(request, cancellation),
                        "OpenAIEmbeddingTransport.generateAsync returned null.");
                currentStage.set(stage);
            } catch (RuntimeException failure) {
                result.completeExceptionally(normalizeFailure(failure));
                return;
            }
            stage.whenComplete((response, failure) -> {
                if (result.isDone()) {
                    return;
                }
                if (failure != null) {
                    result.completeExceptionally(normalizeFailure(failure));
                    return;
                }
                if (response == null) {
                    result.completeExceptionally(new OpenAISdkException("null_response"));
                    return;
                }
                try {
                    appendBatch(request, response);
                    startBatch(end);
                } catch (RuntimeException mappingFailure) {
                    result.completeExceptionally(normalizeFailure(mappingFailure));
                }
            });
        }

        private void appendBatch(OpenAIEmbeddingTransport.Request request, OpenAIEmbeddingTransport.Response response) {
            int expected = request.values().size();
            if (response.items().size() != expected) {
                throw protocol("embedding_count_mismatch");
            }
            ArrayList<OpenAIEmbeddingTransport.Item> ordered =
                    new ArrayList<>(java.util.Collections.nCopies(expected, null));
            for (OpenAIEmbeddingTransport.Item item : response.items()) {
                if (item.index() >= expected || ordered.set(item.index(), item) != null) {
                    throw protocol("invalid_embedding_index");
                }
            }
            String responseModel = response.model() == null ? request.model() : response.model();
            for (OpenAIEmbeddingTransport.Item item : ordered) {
                if (item == null) {
                    throw protocol("missing_embedding_index");
                }
                int dimensions = item.vector().dimensions();
                if (request.dimensions() != null && dimensions != request.dimensions()) {
                    throw protocol("embedding_dimension_mismatch");
                }
                if (observedDimensions == null) {
                    observedDimensions = dimensions;
                } else if (dimensions != observedDimensions) {
                    throw protocol("inconsistent_embedding_dimensions");
                }
                embeddings.add(new Embedding<>(item.vector(), responseModel, null, item.metadata()));
            }
            if (response.usage() != null) {
                usage = usage == null ? response.usage() : usage.fold(response.usage());
            }
            metadata.putAll(response.metadata());
            batchCount++;
        }

        private OpenAIProtocolException protocol(String code) {
            String requestId =
                    metadata.get("openai.requestId") instanceof StateValue.StringValue string ? string.value() : null;
            return new OpenAIProtocolException(
                    "OpenAI embeddings response was internally inconsistent.", requestId, code);
        }
    }
}
