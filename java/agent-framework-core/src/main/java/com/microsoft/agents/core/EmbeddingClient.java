// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Generates ordered provider-neutral embedding batches asynchronously.
 *
 * @param <I> input value type
 * @param <V> vector representation
 * @param <O> options representation
 */
@FunctionalInterface
public interface EmbeddingClient<I, V extends EmbeddingVector, O> extends AutoCloseable {
    /**
     * Generates one embedding for each input value in the same order.
     *
     * @param values ordered input values
     * @param options optional provider options
     * @param cancellation caller-owned cancellation signal
     * @return finite asynchronous batch
     */
    CompletionStage<GeneratedEmbeddings<V, O>> generateAsync(
            List<? extends I> values, O options, RunCancellation cancellation);

    /**
     * Generates embeddings without explicit options.
     *
     * @param values ordered input values
     * @param cancellation caller-owned cancellation signal
     * @return finite asynchronous batch
     */
    default CompletionStage<GeneratedEmbeddings<V, O>> generateAsync(
            List<? extends I> values, RunCancellation cancellation) {
        return generateAsync(values, null, cancellation);
    }

    /**
     * Generates embeddings with a fresh cancellation signal.
     *
     * @param values ordered input values
     * @param options optional provider options
     * @return finite asynchronous batch
     */
    default CompletionStage<GeneratedEmbeddings<V, O>> generateAsync(List<? extends I> values, O options) {
        return generateAsync(values, options, new DefaultRunCancellation());
    }

    /**
     * Generates embeddings without explicit options using a fresh cancellation signal.
     *
     * @param values ordered input values
     * @return finite asynchronous batch
     */
    default CompletionStage<GeneratedEmbeddings<V, O>> generateAsync(List<? extends I> values) {
        return generateAsync(values, null, new DefaultRunCancellation());
    }

    /** Releases provider-owned resources. */
    @Override
    default void close() {}
}
