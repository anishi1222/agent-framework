// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

/**
 * Defines immutable OpenAI Responses API options that have no provider-neutral equivalent.
 *
 * @param reasoningEffort optional reasoning effort
 * @param reasoningSummary optional reasoning-summary detail
 * @param serviceTier optional processing tier
 * @param truncation optional context truncation behavior
 * @param imageOutputFormat optional generated-image output format; setting it enables the Responses
 *     image-generation tool
 * @param background whether the response should run in the background
 * @param includeEncryptedReasoning whether stateless responses should include encrypted reasoning
 */
public record OpenAIResponseOptions(
        OpenAIReasoningEffort reasoningEffort,
        OpenAIReasoningSummary reasoningSummary,
        OpenAIServiceTier serviceTier,
        OpenAITruncation truncation,
        OpenAIImageOutputFormat imageOutputFormat,
        Boolean background,
        boolean includeEncryptedReasoning) {
    /**
     * Returns conservative defaults for a normal foreground response.
     *
     * @return default options
     */
    public static OpenAIResponseOptions defaults() {
        return builder().build();
    }

    /**
     * Creates an options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable {@link OpenAIResponseOptions}. */
    public static final class Builder {
        private OpenAIReasoningEffort reasoningEffort;

        private OpenAIReasoningSummary reasoningSummary;

        private OpenAIServiceTier serviceTier;

        private OpenAITruncation truncation;

        private OpenAIImageOutputFormat imageOutputFormat;

        private Boolean background;

        private boolean includeEncryptedReasoning = true;

        private Builder() {}

        /**
         * Sets reasoning effort.
         *
         * @param reasoningEffort effort
         * @return this builder
         */
        public Builder reasoningEffort(OpenAIReasoningEffort reasoningEffort) {
            this.reasoningEffort = java.util.Objects.requireNonNull(reasoningEffort, "reasoningEffort");
            return this;
        }

        /**
         * Sets reasoning-summary detail.
         *
         * @param reasoningSummary summary detail
         * @return this builder
         */
        public Builder reasoningSummary(OpenAIReasoningSummary reasoningSummary) {
            this.reasoningSummary = java.util.Objects.requireNonNull(reasoningSummary, "reasoningSummary");
            return this;
        }

        /**
         * Sets the service tier.
         *
         * @param serviceTier service tier
         * @return this builder
         */
        public Builder serviceTier(OpenAIServiceTier serviceTier) {
            this.serviceTier = java.util.Objects.requireNonNull(serviceTier, "serviceTier");
            return this;
        }

        /**
         * Sets truncation behavior.
         *
         * @param truncation truncation behavior
         * @return this builder
         */
        public Builder truncation(OpenAITruncation truncation) {
            this.truncation = java.util.Objects.requireNonNull(truncation, "truncation");
            return this;
        }

        /**
         * Sets the generated-image output format and enables the Responses image-generation tool.
         *
         * @param imageOutputFormat supported output format
         * @return this builder
         */
        public Builder imageOutputFormat(OpenAIImageOutputFormat imageOutputFormat) {
            this.imageOutputFormat = java.util.Objects.requireNonNull(imageOutputFormat, "imageOutputFormat");
            return this;
        }

        /**
         * Sets background execution.
         *
         * @param background background preference
         * @return this builder
         */
        public Builder background(boolean background) {
            this.background = background;
            return this;
        }

        /**
         * Sets whether encrypted reasoning is requested for stateless replay.
         *
         * @param includeEncryptedReasoning inclusion preference
         * @return this builder
         */
        public Builder includeEncryptedReasoning(boolean includeEncryptedReasoning) {
            this.includeEncryptedReasoning = includeEncryptedReasoning;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return response options
         */
        public OpenAIResponseOptions build() {
            return new OpenAIResponseOptions(
                    reasoningEffort,
                    reasoningSummary,
                    serviceTier,
                    truncation,
                    imageOutputFormat,
                    background,
                    includeEncryptedReasoning);
        }
    }
}
