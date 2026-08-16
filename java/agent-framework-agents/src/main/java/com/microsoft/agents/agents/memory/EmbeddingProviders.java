// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.EmbeddingClient;
import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.GeneratedEmbeddings;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Adapts general batch embedding clients to the scoped memory embedding contract. */
public final class EmbeddingProviders {
    private EmbeddingProviders() {}

    /**
     * Adapts a client without per-request provider options.
     *
     * <p>The caller retains ownership of the client.
     *
     * @param client batch embedding client
     * @param <O> provider option type
     * @return scoped single-vector provider
     */
    public static <O> EmbeddingProvider fromClient(EmbeddingClient<String, FloatEmbeddingVector, O> client) {
        return fromClientWithOptions(client, request -> null);
    }

    /**
     * Adapts a client with fixed provider options.
     *
     * <p>The caller retains ownership of the client.
     *
     * @param client batch embedding client
     * @param options fixed options, or {@code null}
     * @param <O> provider option type
     * @return scoped single-vector provider
     */
    public static <O> EmbeddingProvider fromClient(EmbeddingClient<String, FloatEmbeddingVector, O> client, O options) {
        return fromClientWithOptions(client, request -> options);
    }

    /**
     * Adapts a client with options resolved from each tenant-scoped request.
     *
     * <p>The caller retains ownership of the client. The resolver can use the request scope to
     * select provider metadata or routing options without exposing that scope to the embedding
     * provider SDK.
     *
     * @param client batch embedding client
     * @param optionsResolver request-to-options resolver
     * @param <O> provider option type
     * @return scoped single-vector provider
     */
    public static <O> EmbeddingProvider fromClientWithOptions(
            EmbeddingClient<String, FloatEmbeddingVector, O> client,
            Function<EmbeddingRequest, ? extends O> optionsResolver) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(optionsResolver, "optionsResolver");
        return (request, cancellation) -> generate(client, optionsResolver, request, cancellation);
    }

    private static <O> CompletionStage<EmbeddingVector> generate(
            EmbeddingClient<String, FloatEmbeddingVector, O> client,
            Function<EmbeddingRequest, ? extends O> optionsResolver,
            EmbeddingRequest request,
            RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        CompletionStage<GeneratedEmbeddings<FloatEmbeddingVector, O>> stage;
        try {
            stage = client.generateAsync(List.of(request.text()), optionsResolver.apply(request), cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedStage(failure);
        }
        if (stage == null) {
            return CompletableFuture.failedStage(
                    new AgentExecutionException("EmbeddingClient.generateAsync returned null."));
        }
        return stage.thenApply(generated -> {
            if (generated == null || generated.size() != 1) {
                throw new AgentExecutionException(
                        "EmbeddingClient must return exactly one embedding for a memory request.");
            }
            return new EmbeddingVector(generated.get(0).vector().values());
        });
    }
}
