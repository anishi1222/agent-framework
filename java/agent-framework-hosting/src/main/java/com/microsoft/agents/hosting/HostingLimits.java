// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.SerializationLimits;
import java.time.Duration;

/**
 * Defines mandatory finite bounds for hosted parsing, execution, streaming, and continuation state.
 */
public final class HostingLimits {
    private static final long DEFAULT_REQUEST_BYTES = 1024L * 1024L;

    private static final long DEFAULT_RESPONSE_BYTES = 4L * 1024L * 1024L;

    private static final long MINIMUM_RESPONSE_BYTES = 153L;

    private final long maxRequestBytes;

    private final long maxResponseBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxNumericTokenLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private final int maxConcurrentRuns;

    private final int maxSseBufferedEvents;

    private final int maxWebSocketBufferedMessages;

    private final int maxWebSocketFrameBytes;

    private final int maxEventsPerRun;

    private final Duration idleTimeout;

    private final Duration runTimeout;

    private final int maxProcessLocalContinuations;

    private final Duration continuationTtl;

    private HostingLimits(Builder builder) {
        maxRequestBytes = HostingValidation.positive(builder.maxRequestBytes, "maxRequestBytes");
        maxResponseBytes = HostingValidation.positive(builder.maxResponseBytes, "maxResponseBytes");
        if (maxResponseBytes < MINIMUM_RESPONSE_BYTES) {
            throw new com.microsoft.agents.core.ValidationException("maxResponseBytes must be at least "
                    + MINIMUM_RESPONSE_BYTES
                    + " bytes so every minimal Java-hosting error envelope can be encoded.");
        }
        maxNestingDepth = HostingValidation.positive(builder.maxNestingDepth, "maxNestingDepth");
        maxStringLength = HostingValidation.positive(builder.maxStringLength, "maxStringLength");
        maxNumericTokenLength = HostingValidation.positive(builder.maxNumericTokenLength, "maxNumericTokenLength");
        maxCollectionEntries = HostingValidation.positive(builder.maxCollectionEntries, "maxCollectionEntries");
        maxConcurrentRequests = HostingValidation.positive(builder.maxConcurrentRequests, "maxConcurrentRequests");
        maxConcurrentRuns = HostingValidation.positive(builder.maxConcurrentRuns, "maxConcurrentRuns");
        maxSseBufferedEvents = HostingValidation.positive(builder.maxSseBufferedEvents, "maxSseBufferedEvents");
        maxWebSocketBufferedMessages =
                HostingValidation.positive(builder.maxWebSocketBufferedMessages, "maxWebSocketBufferedMessages");
        maxWebSocketFrameBytes = HostingValidation.positive(builder.maxWebSocketFrameBytes, "maxWebSocketFrameBytes");
        maxEventsPerRun = HostingValidation.positive(builder.maxEventsPerRun, "maxEventsPerRun");
        idleTimeout = HostingValidation.positive(builder.idleTimeout, "idleTimeout");
        runTimeout = HostingValidation.positive(builder.runTimeout, "runTimeout");
        maxProcessLocalContinuations =
                HostingValidation.positive(builder.maxProcessLocalContinuations, "maxProcessLocalContinuations");
        continuationTtl = HostingValidation.positive(builder.continuationTtl, "continuationTtl");
        if (maxWebSocketFrameBytes > maxRequestBytes) {
            throw new com.microsoft.agents.core.ValidationException(
                    "maxWebSocketFrameBytes must not exceed maxRequestBytes.");
        }
    }

    /**
     * Returns conservative defaults.
     *
     * @return default limits
     */
    public static HostingLimits defaults() {
        return builder().build();
    }

    /**
     * Creates a limits builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the request-body bound.
     *
     * @return bytes
     */
    public long maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Returns the encoded response/event bound.
     *
     * @return bytes
     */
    public long maxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * Returns the JSON nesting bound.
     *
     * @return depth
     */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * Returns the decoded string bound.
     *
     * @return characters
     */
    public int maxStringLength() {
        return maxStringLength;
    }

    /**
     * Returns the numeric token bound.
     *
     * @return characters
     */
    public int maxNumericTokenLength() {
        return maxNumericTokenLength;
    }

    /**
     * Returns the per-object or per-array entry bound.
     *
     * @return entries
     */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /**
     * Returns the transport request concurrency bound.
     *
     * @return requests
     */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /**
     * Returns the active execution bound.
     *
     * @return runs
     */
    public int maxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /**
     * Returns the SSE bridge buffer bound.
     *
     * @return events
     */
    public int maxSseBufferedEvents() {
        return maxSseBufferedEvents;
    }

    /**
     * Returns the WebSocket outbound buffer bound.
     *
     * @return messages
     */
    public int maxWebSocketBufferedMessages() {
        return maxWebSocketBufferedMessages;
    }

    /**
     * Returns the complete WebSocket text-message bound across fragments.
     *
     * @return bytes
     */
    public int maxWebSocketFrameBytes() {
        return maxWebSocketFrameBytes;
    }

    /**
     * Returns the event count bound for one run.
     *
     * @return events
     */
    public int maxEventsPerRun() {
        return maxEventsPerRun;
    }

    /**
     * Returns the transport inactivity timeout.
     *
     * @return timeout
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /**
     * Returns the logical run timeout.
     *
     * @return timeout
     */
    public Duration runTimeout() {
        return runTimeout;
    }

    /**
     * Returns the process-local continuation capacity.
     *
     * @return continuations
     */
    public int maxProcessLocalContinuations() {
        return maxProcessLocalContinuations;
    }

    /**
     * Returns process-local continuation lifetime.
     *
     * @return lifetime
     */
    public Duration continuationTtl() {
        return continuationTtl;
    }

    /**
     * Returns parser limits aligned with this hosting configuration.
     *
     * @return serialization limits
     */
    public SerializationLimits requestSerializationLimits() {
        return new SerializationLimits(
                maxRequestBytes, maxNestingDepth, maxStringLength, maxNumericTokenLength, maxCollectionEntries);
    }

    /** Builds immutable hosting limits. */
    public static final class Builder {
        private long maxRequestBytes = DEFAULT_REQUEST_BYTES;

        private long maxResponseBytes = DEFAULT_RESPONSE_BYTES;

        private int maxNestingDepth = 64;

        private int maxStringLength = 256 * 1024;

        private int maxNumericTokenLength = 256;

        private int maxCollectionEntries = 10_000;

        private int maxConcurrentRequests = 128;

        private int maxConcurrentRuns = 64;

        private int maxSseBufferedEvents = 64;

        private int maxWebSocketBufferedMessages = 64;

        private int maxWebSocketFrameBytes = 256 * 1024;

        private int maxEventsPerRun = 10_000;

        private Duration idleTimeout = Duration.ofSeconds(60);

        private Duration runTimeout = Duration.ofMinutes(5);

        private int maxProcessLocalContinuations = 1_000;

        private Duration continuationTtl = Duration.ofMinutes(10);

        private Builder() {}

        /** Sets maximum request bytes. */
        public Builder maxRequestBytes(long value) {
            maxRequestBytes = value;
            return this;
        }

        /** Sets maximum response bytes. */
        public Builder maxResponseBytes(long value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets maximum JSON nesting depth. */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /** Sets maximum decoded string length. */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /** Sets maximum numeric token length. */
        public Builder maxNumericTokenLength(int value) {
            maxNumericTokenLength = value;
            return this;
        }

        /** Sets maximum entries in one collection. */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /** Sets maximum concurrent transport requests. */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /** Sets maximum concurrent logical runs. */
        public Builder maxConcurrentRuns(int value) {
            maxConcurrentRuns = value;
            return this;
        }

        /** Sets maximum buffered SSE events. */
        public Builder maxSseBufferedEvents(int value) {
            maxSseBufferedEvents = value;
            return this;
        }

        /** Sets maximum buffered WebSocket messages. */
        public Builder maxWebSocketBufferedMessages(int value) {
            maxWebSocketBufferedMessages = value;
            return this;
        }

        /** Sets maximum complete WebSocket text-message bytes. */
        public Builder maxWebSocketFrameBytes(int value) {
            maxWebSocketFrameBytes = value;
            return this;
        }

        /** Sets maximum emitted events per run. */
        public Builder maxEventsPerRun(int value) {
            maxEventsPerRun = value;
            return this;
        }

        /** Sets transport inactivity timeout. */
        public Builder idleTimeout(Duration value) {
            idleTimeout = value;
            return this;
        }

        /** Sets logical run timeout. */
        public Builder runTimeout(Duration value) {
            runTimeout = value;
            return this;
        }

        /** Sets process-local continuation capacity. */
        public Builder maxProcessLocalContinuations(int value) {
            maxProcessLocalContinuations = value;
            return this;
        }

        /** Sets process-local continuation lifetime. */
        public Builder continuationTtl(Duration value) {
            continuationTtl = value;
            return this;
        }

        /**
         * Creates immutable limits.
         *
         * @return limits
         */
        public HostingLimits build() {
            return new HostingLimits(this);
        }
    }
}
