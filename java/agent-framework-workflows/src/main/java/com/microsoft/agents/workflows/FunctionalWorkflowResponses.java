// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.StateCodec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Stores explicitly typed responses used to resume a functional workflow. */
public final class FunctionalWorkflowResponses {
    private static final FunctionalWorkflowResponses EMPTY = new FunctionalWorkflowResponses(Map.of());

    private final Map<String, EncodedState> values;

    private FunctionalWorkflowResponses(Map<String, EncodedState> values) {
        TreeMap<String, EncodedState> sorted = new TreeMap<>();
        values.forEach((requestId, value) -> sorted.put(
                WorkflowValidation.requireNonBlank(requestId, "requestId"), Objects.requireNonNull(value, "response")));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * Returns an empty response set.
     *
     * @return shared empty response set
     */
    public static FunctionalWorkflowResponses empty() {
        return EMPTY;
    }

    /**
     * Creates a response builder.
     *
     * @return empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates one typed response.
     *
     * @param requestId pending request identifier
     * @param valueType response value type
     * @param codec response codec
     * @param value response value
     * @param <T> response type
     * @return one-entry response set
     */
    public static <T> FunctionalWorkflowResponses of(
            String requestId, Class<T> valueType, StateCodec<T> codec, T value) {
        return builder().put(requestId, valueType, codec, value).build();
    }

    /**
     * Returns the immutable encoded responses sorted by request identifier.
     *
     * @return encoded responses
     */
    public Map<String, EncodedState> values() {
        return values;
    }

    /** Builds immutable typed workflow responses. */
    public static final class Builder {
        private final LinkedHashMap<String, EncodedState> values = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Adds one typed response.
         *
         * @param requestId pending request identifier
         * @param valueType response value type
         * @param codec response codec
         * @param value response value
         * @param <T> response type
         * @return this builder
         */
        public <T> Builder put(String requestId, Class<T> valueType, StateCodec<T> codec, T value) {
            String checkedId = WorkflowValidation.requireNonBlank(requestId, "requestId");
            Objects.requireNonNull(valueType, "valueType");
            Objects.requireNonNull(codec, "codec");
            WorkflowValidation.requireCodec(codec);
            T checkedValue = valueType.cast(value);
            EncodedState encoded = FunctionalStateCodecSupport.encode(codec, checkedValue);
            if (values.putIfAbsent(checkedId, encoded) != null) {
                throw new WorkflowValidationException("Duplicate response for request '" + checkedId + "'.");
            }
            return this;
        }

        /**
         * Adds one response that is already encoded with its declared codec identity.
         *
         * <p>This is primarily useful at protocol boundaries where the caller received a
         * JSON-shaped value together with the pending request's expected type identifier and
         * version.
         *
         * @param requestId pending request identifier
         * @param value encoded response value
         * @return this builder
         */
        public Builder putEncoded(String requestId, EncodedState value) {
            String checkedId = WorkflowValidation.requireNonBlank(requestId, "requestId");
            EncodedState checkedValue = Objects.requireNonNull(value, "value");
            if (values.putIfAbsent(checkedId, checkedValue) != null) {
                throw new WorkflowValidationException("Duplicate response for request '" + checkedId + "'.");
            }
            return this;
        }

        /**
         * Creates the immutable response set.
         *
         * @return workflow responses
         */
        public FunctionalWorkflowResponses build() {
            return values.isEmpty() ? EMPTY : new FunctionalWorkflowResponses(values);
        }
    }
}
