// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Contains an ordered immutable embedding batch and its generation metadata.
 *
 * @param <V> vector representation
 * @param <O> options representation
 * @param embeddings ordered embeddings corresponding one-for-one with request values
 * @param options exact options supplied by the caller, or {@code null}
 * @param usage optional folded provider usage
 * @param metadata immutable JSON-shaped batch metadata
 */
public record GeneratedEmbeddings<V extends EmbeddingVector, O>(
        List<Embedding<V>> embeddings, O options, UsageDetails usage, Map<String, StateValue> metadata)
        implements Iterable<Embedding<V>> {
    /** Creates and defensively copies a generated batch. */
    public GeneratedEmbeddings {
        embeddings = CoreValidation.copyList(embeddings, "embeddings");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates a generated batch without batch metadata.
     *
     * @param embeddings ordered embeddings
     * @param options caller options
     * @param usage optional usage
     */
    public GeneratedEmbeddings(List<Embedding<V>> embeddings, O options, UsageDetails usage) {
        this(embeddings, options, usage, Map.of());
    }

    /**
     * Returns the number of generated embeddings.
     *
     * @return batch size
     */
    public int size() {
        return embeddings.size();
    }

    /**
     * Returns one generated embedding.
     *
     * @param index zero-based index
     * @return embedding
     */
    public Embedding<V> get(int index) {
        return embeddings.get(index);
    }

    @Override
    public Iterator<Embedding<V>> iterator() {
        return embeddings.iterator();
    }
}
