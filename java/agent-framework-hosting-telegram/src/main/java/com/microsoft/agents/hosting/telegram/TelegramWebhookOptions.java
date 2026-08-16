// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Configures strict Telegram webhook parsing, dispatch, aggregation, and deadlines. */
public final class TelegramWebhookOptions {
    /** Telegram's documented {@code sendMessage} text limit in UTF-16 code units. */
    public static final int TELEGRAM_MAX_TEXT_LENGTH = 4096;

    private static final Pattern SECRET_TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,256}");

    private final long botId;

    private final String routeId;

    private final TelegramSecret webhookSecretToken;

    private final boolean streaming;

    private final Duration processingTimeout;

    private final int maxUpdateBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxInboundTextLength;

    private final int maxStreamingEvents;

    private final int maxOutboundTextLength;

    private TelegramWebhookOptions(Builder builder) {
        botId = TelegramValidation.positive(builder.botId, "botId");
        routeId = TelegramValidation.nonBlank(builder.routeId, "routeId");
        webhookSecretToken = Objects.requireNonNull(builder.webhookSecretToken, "webhookSecretToken");
        if (!SECRET_TOKEN.matcher(webhookSecretToken.value()).matches()) {
            throw new IllegalArgumentException(
                    "webhookSecretToken must use 1-256 ASCII letters, digits, underscores, or hyphens.");
        }
        streaming = builder.streaming;
        processingTimeout = TelegramValidation.positive(builder.processingTimeout, "processingTimeout");
        maxUpdateBytes = TelegramValidation.positive(builder.maxUpdateBytes, "maxUpdateBytes");
        maxNestingDepth = TelegramValidation.positive(builder.maxNestingDepth, "maxNestingDepth");
        maxStringLength = TelegramValidation.positive(builder.maxStringLength, "maxStringLength");
        maxCollectionEntries = TelegramValidation.positive(builder.maxCollectionEntries, "maxCollectionEntries");
        maxInboundTextLength = TelegramValidation.positive(builder.maxInboundTextLength, "maxInboundTextLength");
        maxStreamingEvents = TelegramValidation.positive(builder.maxStreamingEvents, "maxStreamingEvents");
        maxOutboundTextLength = TelegramValidation.positive(builder.maxOutboundTextLength, "maxOutboundTextLength");
        if (maxInboundTextLength > maxStringLength) {
            throw new IllegalArgumentException("maxInboundTextLength must not exceed maxStringLength.");
        }
        if (maxOutboundTextLength > TELEGRAM_MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "maxOutboundTextLength must not exceed Telegram's 4096-character limit.");
        }
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
     * Returns the Telegram bot's numeric user identifier.
     *
     * @return bot identifier
     */
    public long botId() {
        return botId;
    }

    /**
     * Returns the registered generic-hosting agent route identifier.
     *
     * @return route identifier
     */
    public String routeId() {
        return routeId;
    }

    /**
     * Reports whether the adapter uses generic-hosting streaming execution.
     *
     * @return streaming setting
     */
    public boolean streaming() {
        return streaming;
    }

    /**
     * Returns the complete webhook processing deadline.
     *
     * @return processing timeout
     */
    public Duration processingTimeout() {
        return processingTimeout;
    }

    /**
     * Returns the maximum webhook JSON bytes.
     *
     * @return byte limit
     */
    public int maxUpdateBytes() {
        return maxUpdateBytes;
    }

    /**
     * Returns the maximum JSON nesting depth.
     *
     * @return nesting limit
     */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * Returns the maximum JSON string and member-name length.
     *
     * @return string limit
     */
    public int maxStringLength() {
        return maxStringLength;
    }

    /**
     * Returns the maximum entries per JSON object or array.
     *
     * @return collection limit
     */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /**
     * Returns the maximum accepted inbound text length.
     *
     * @return text limit
     */
    public int maxInboundTextLength() {
        return maxInboundTextLength;
    }

    /**
     * Returns the maximum number of streaming updates aggregated for one webhook.
     *
     * @return event limit
     */
    public int maxStreamingEvents() {
        return maxStreamingEvents;
    }

    /**
     * Returns the maximum outbound text length.
     *
     * @return outbound text limit
     */
    public int maxOutboundTextLength() {
        return maxOutboundTextLength;
    }

    String webhookSecretToken() {
        return webhookSecretToken.value();
    }

    @Override
    public String toString() {
        return "TelegramWebhookOptions{botId="
                + botId
                + ", routeId='"
                + routeId
                + "', webhookSecretToken=[REDACTED], streaming="
                + streaming
                + ", processingTimeout="
                + processingTimeout
                + ", maxUpdateBytes="
                + maxUpdateBytes
                + ", maxNestingDepth="
                + maxNestingDepth
                + ", maxStringLength="
                + maxStringLength
                + ", maxCollectionEntries="
                + maxCollectionEntries
                + ", maxInboundTextLength="
                + maxInboundTextLength
                + ", maxStreamingEvents="
                + maxStreamingEvents
                + ", maxOutboundTextLength="
                + maxOutboundTextLength
                + '}';
    }

    /** Builds immutable {@link TelegramWebhookOptions}. */
    public static final class Builder {
        private long botId;

        private String routeId;

        private TelegramSecret webhookSecretToken;

        private boolean streaming;

        private Duration processingTimeout = Duration.ofSeconds(10);

        private int maxUpdateBytes = 256 * 1024;

        private int maxNestingDepth = 32;

        private int maxStringLength = 16 * 1024;

        private int maxCollectionEntries = 512;

        private int maxInboundTextLength = TELEGRAM_MAX_TEXT_LENGTH;

        private int maxStreamingEvents = 1024;

        private int maxOutboundTextLength = TELEGRAM_MAX_TEXT_LENGTH;

        private Builder() {}

        /**
         * Sets the Telegram bot's numeric user identifier.
         *
         * @param value bot identifier
         * @return this builder
         */
        public Builder botId(long value) {
            botId = value;
            return this;
        }

        /**
         * Sets the registered generic-hosting agent route identifier.
         *
         * @param value route identifier
         * @return this builder
         */
        public Builder routeId(String value) {
            routeId = value;
            return this;
        }

        /**
         * Sets the webhook secret token.
         *
         * @param value Telegram webhook secret token
         * @return this builder
         */
        public Builder webhookSecretToken(String value) {
            webhookSecretToken = TelegramSecret.of(value);
            return this;
        }

        /**
         * Sets an already wrapped webhook secret token.
         *
         * @param value Telegram webhook secret token
         * @return this builder
         */
        public Builder webhookSecretToken(TelegramSecret value) {
            webhookSecretToken = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Selects finite or streaming generic-hosting execution.
         *
         * @param value {@code true} for streaming aggregation
         * @return this builder
         */
        public Builder streaming(boolean value) {
            streaming = value;
            return this;
        }

        /**
         * Sets the complete webhook processing deadline.
         *
         * @param value positive timeout
         * @return this builder
         */
        public Builder processingTimeout(Duration value) {
            processingTimeout = value;
            return this;
        }

        /**
         * Sets the maximum webhook JSON bytes.
         *
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxUpdateBytes(int value) {
            maxUpdateBytes = value;
            return this;
        }

        /**
         * Sets the maximum JSON nesting depth.
         *
         * @param value positive nesting limit
         * @return this builder
         */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /**
         * Sets the maximum JSON string and member-name length.
         *
         * @param value positive string limit
         * @return this builder
         */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /**
         * Sets the maximum entries per JSON object or array.
         *
         * @param value positive collection limit
         * @return this builder
         */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /**
         * Sets the maximum accepted inbound text length.
         *
         * @param value positive text limit
         * @return this builder
         */
        public Builder maxInboundTextLength(int value) {
            maxInboundTextLength = value;
            return this;
        }

        /**
         * Sets the maximum number of aggregated streaming events.
         *
         * @param value positive event limit
         * @return this builder
         */
        public Builder maxStreamingEvents(int value) {
            maxStreamingEvents = value;
            return this;
        }

        /**
         * Sets the maximum outbound text length.
         *
         * @param value positive limit no greater than 4096
         * @return this builder
         */
        public Builder maxOutboundTextLength(int value) {
            maxOutboundTextLength = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public TelegramWebhookOptions build() {
            return new TelegramWebhookOptions(this);
        }
    }
}
