// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Describes why generation ended while preserving provider-neutral custom reasons.
 */
public final class FinishReason {
    /** Normal completion. */
    public static final FinishReason STOP = new FinishReason("stop");

    /** The configured output length was reached. */
    public static final FinishReason LENGTH = new FinishReason("length");

    /** Generation stopped to request tool calls. */
    public static final FinishReason TOOL_CALLS = new FinishReason("toolCalls");

    /** Generation was stopped by content filtering. */
    public static final FinishReason CONTENT_FILTER = new FinishReason("contentFilter");

    private final String value;

    private FinishReason(String value) {
        this.value = CoreValidation.requireNonBlank(value, "value");
    }

    /**
     * Creates a finish reason and normalizes known cross-language spellings.
     *
     * @param value non-blank finish-reason value
     * @return a known singleton or a custom finish reason
     * @throws NullPointerException when {@code value} is {@code null}
     * @throws ValidationException when {@code value} is blank
     */
    public static FinishReason of(String value) {
        return switch (CoreValidation.requireNonBlank(value, "value")) {
            case "stop" -> STOP;
            case "length" -> LENGTH;
            case "toolCalls", "tool_calls", "tool-calls" -> TOOL_CALLS;
            case "contentFilter", "content_filter", "content-filter" -> CONTENT_FILTER;
            default -> new FinishReason(value);
        };
    }

    /**
     * Returns the stable camel-case value used by the Java model.
     *
     * @return finish-reason value
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FinishReason reason && value.equals(reason.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
