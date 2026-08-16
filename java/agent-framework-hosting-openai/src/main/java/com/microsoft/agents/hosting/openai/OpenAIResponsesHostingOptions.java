// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

/** Configures strict OpenAI Responses request mapping and process-local conversation retention. */
public final class OpenAIResponsesHostingOptions {
    private final OpenAIResponsesRunOptionsMapper runOptionsMapper;

    private final int maxConversationEntries;

    private final Duration conversationTimeToLive;

    private final int maxStoreRetries;

    private OpenAIResponsesHostingOptions(Builder builder) {
        runOptionsMapper = java.util.Objects.requireNonNull(builder.runOptionsMapper, "runOptionsMapper");
        if (builder.maxConversationEntries <= 0) {
            throw new IllegalArgumentException("maxConversationEntries must be greater than zero.");
        }
        maxConversationEntries = builder.maxConversationEntries;
        conversationTimeToLive =
                java.util.Objects.requireNonNull(builder.conversationTimeToLive, "conversationTimeToLive");
        if (conversationTimeToLive.isZero() || conversationTimeToLive.isNegative()) {
            throw new IllegalArgumentException("conversationTimeToLive must be positive.");
        }
        if (builder.maxStoreRetries <= 0) {
            throw new IllegalArgumentException("maxStoreRetries must be greater than zero.");
        }
        maxStoreRetries = builder.maxStoreRetries;
    }

    /**
     * Returns secure defaults that reject caller attempts to override agent generation or tools.
     *
     * @return default options
     */
    public static OpenAIResponsesHostingOptions defaults() {
        return builder().build();
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the request-settings mapper.
     *
     * @return mapper
     */
    public OpenAIResponsesRunOptionsMapper runOptionsMapper() {
        return runOptionsMapper;
    }

    /**
     * Returns the default in-memory conversation capacity.
     *
     * @return entry capacity
     */
    public int maxConversationEntries() {
        return maxConversationEntries;
    }

    /**
     * Returns the default conversation inactivity lifetime.
     *
     * @return time to live
     */
    public Duration conversationTimeToLive() {
        return conversationTimeToLive;
    }

    /**
     * Returns the optimistic-store retry bound.
     *
     * @return retry count
     */
    public int maxStoreRetries() {
        return maxStoreRetries;
    }

    /**
     * Rejects settings that cannot safely override a self-contained hosted agent.
     *
     * <p>The model value is informational and {@code max_tool_calls} maps directly to the generic
     * function-call bound. All other generation and tool-selection settings require an explicit
     * application mapper.
     *
     * @param request validated request information
     * @return conservative provider-neutral options
     */
    public static RunOptions rejectRequestSettings(OpenAIResponsesRequestInfo request) {
        java.util.Objects.requireNonNull(request, "request");
        ArrayList<String> unsupported = new ArrayList<>();
        if (request.temperature() != null) {
            unsupported.add("temperature");
        }
        if (request.topP() != null) {
            unsupported.add("top_p");
        }
        if (request.maxOutputTokens() != null) {
            unsupported.add("max_output_tokens");
        }
        if (request.instructions() != null) {
            unsupported.add("instructions");
        }
        if (!request.tools().isEmpty()) {
            unsupported.add("tools");
        }
        if (request.toolChoice() != null) {
            unsupported.add("tool_choice");
        }
        if (request.parallelToolCalls() != null) {
            unsupported.add("parallel_tool_calls");
        }
        if (!unsupported.isEmpty()) {
            String parameter = unsupported.getFirst();
            throw new HostingException(new HostingError(
                    HostingErrorCode.UNPROCESSABLE,
                    "The following request setting(s) are not supported by this agent endpoint: "
                            + String.join(", ", unsupported)
                            + ". Configure OpenAIResponsesHostingOptions.runOptionsMapper to map them explicitly.",
                    false,
                    Map.of(
                            "openaiCode", StateValue.string("unsupported_parameter"),
                            "param", StateValue.string(parameter))));
        }
        return request.maxToolCalls() == null
                ? RunOptions.empty()
                : RunOptions.builder().maxFunctionCalls(request.maxToolCalls()).build();
    }

    /** Builds immutable {@link OpenAIResponsesHostingOptions}. */
    public static final class Builder {
        private OpenAIResponsesRunOptionsMapper runOptionsMapper = OpenAIResponsesHostingOptions::rejectRequestSettings;

        private int maxConversationEntries = 1_000;

        private Duration conversationTimeToLive = Duration.ofMinutes(30);

        private int maxStoreRetries = 3;

        private Builder() {}

        /**
         * Sets the explicit request-settings mapper.
         *
         * @param value mapper
         * @return this builder
         */
        public Builder runOptionsMapper(OpenAIResponsesRunOptionsMapper value) {
            runOptionsMapper = java.util.Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the default in-memory conversation capacity.
         *
         * @param value positive capacity
         * @return this builder
         */
        public Builder maxConversationEntries(int value) {
            maxConversationEntries = value;
            return this;
        }

        /**
         * Sets the default conversation inactivity lifetime.
         *
         * @param value positive time to live
         * @return this builder
         */
        public Builder conversationTimeToLive(Duration value) {
            conversationTimeToLive = java.util.Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the optimistic-store retry bound.
         *
         * @param value positive retry count
         * @return this builder
         */
        public Builder maxStoreRetries(int value) {
            maxStoreRetries = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public OpenAIResponsesHostingOptions build() {
            return new OpenAIResponsesHostingOptions(this);
        }
    }
}
