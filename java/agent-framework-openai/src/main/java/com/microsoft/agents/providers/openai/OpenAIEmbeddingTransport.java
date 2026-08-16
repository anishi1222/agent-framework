// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.FloatEmbeddingVector;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Isolates the OpenAI embeddings protocol and SDK from the public embedding client. */
@FunctionalInterface
public interface OpenAIEmbeddingTransport extends AutoCloseable {
    /**
     * Generates one provider response for a bounded request batch.
     *
     * @param request provider request
     * @param cancellation caller-owned cancellation signal
     * @return finite provider response
     */
    CompletionStage<Response> generateAsync(Request request, RunCancellation cancellation);

    /** Releases provider-owned resources. */
    @Override
    default void close() {}

    /**
     * Provider request model.
     *
     * @param values non-empty ordered text values
     * @param model required model or deployment identifier
     * @param dimensions optional requested dimensions
     * @param encodingFormat requested wire encoding
     * @param user optional stable end-user identifier
     * @param metadata immutable transport metadata
     */
    record Request(
            List<String> values,
            String model,
            Integer dimensions,
            OpenAIEmbeddingEncodingFormat encodingFormat,
            String user,
            Map<String, StateValue> metadata) {
        /** Creates and validates a provider request. */
        public Request {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (values.isEmpty()) {
                throw new IllegalArgumentException("values must not be empty.");
            }
            Objects.requireNonNull(model, "model");
            if (model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank.");
            }
            if (dimensions != null && (dimensions <= 0 || dimensions > FloatEmbeddingVector.MAX_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "dimensions must be between 1 and " + FloatEmbeddingVector.MAX_DIMENSIONS + ".");
            }
            encodingFormat = Objects.requireNonNull(encodingFormat, "encodingFormat");
            if (user != null && user.isBlank()) {
                throw new IllegalArgumentException("user must not be blank.");
            }
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }
    }

    /**
     * One indexed provider vector.
     *
     * @param index zero-based index within the request batch
     * @param vector immutable finite vector
     * @param metadata immutable item metadata
     */
    record Item(int index, FloatEmbeddingVector vector, Map<String, StateValue> metadata) {
        /** Creates and validates one item. */
        public Item {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative.");
            }
            vector = Objects.requireNonNull(vector, "vector");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }

        /**
         * Creates an item without metadata.
         *
         * @param index zero-based index
         * @param vector embedding vector
         */
        public Item(int index, FloatEmbeddingVector vector) {
            this(index, vector, Map.of());
        }
    }

    /**
     * Provider response model.
     *
     * @param items indexed response items
     * @param model optional resolved provider model
     * @param usage optional request usage
     * @param metadata immutable response metadata
     */
    record Response(List<Item> items, String model, UsageDetails usage, Map<String, StateValue> metadata) {
        /** Creates and validates a provider response. */
        public Response {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (model != null && model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank.");
            }
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }
    }
}
