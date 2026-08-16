// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;

/**
 * Configures bounded, versioned Valkey conversation history.
 *
 * @param client standalone client, authentication, TLS, name, and deadline options
 * @param partition explicit tenant/isolation/agent context
 * @param providerId stable HistoryProvider identifier
 * @param keyPrefix non-secret Valkey namespace prefix
 * @param maxStoredMessages maximum retained list entries
 * @param maxLoadedMessages maximum tail entries returned by one load
 * @param timeToLive optional sliding TTL refreshed atomically on successful append or replay
 * @param maxMessageBytes maximum encoded bytes for one versioned message envelope
 * @param maxDocumentBytes maximum aggregate encoded bytes accepted by one append or load
 */
public record ValkeyHistoryOptions(
        ValkeyClientOptions client,
        ValkeyPartitionContext partition,
        String providerId,
        String keyPrefix,
        int maxStoredMessages,
        int maxLoadedMessages,
        Duration timeToLive,
        int maxMessageBytes,
        int maxDocumentBytes) {
    /** Default history-provider identifier. */
    public static final String DEFAULT_PROVIDER_ID = "valkey-history";

    /** Default collision-safe key namespace prefix. */
    public static final String DEFAULT_KEY_PREFIX = "agent-framework:history:v1";

    /** Creates validated bounded history options. */
    public ValkeyHistoryOptions {
        client = ValkeyValidation.requireNonNull(client, "client");
        partition = ValkeyValidation.requireNonNull(partition, "partition");
        providerId = ValkeyValidation.boundedIdentifier(providerId, "providerId", 256);
        keyPrefix = ValkeyValidation.keyPrefix(keyPrefix);
        if (maxStoredMessages <= 0 || maxStoredMessages > 100_000) {
            throw new ValidationException("maxStoredMessages must be between 1 and 100000.");
        }
        if (maxLoadedMessages <= 0 || maxLoadedMessages > 10_000 || maxLoadedMessages > maxStoredMessages) {
            throw new ValidationException(
                    "maxLoadedMessages must be between 1 and 10000 and not exceed maxStoredMessages.");
        }
        if (timeToLive != null) {
            long ttlMillis;
            try {
                ttlMillis = timeToLive.toMillis();
            } catch (ArithmeticException exception) {
                throw new ValidationException("timeToLive is outside the supported range.", exception);
            }
            if (timeToLive.isNegative()
                    || timeToLive.isZero()
                    || ttlMillis <= 0
                    || timeToLive.compareTo(Duration.ofDays(3650)) > 0) {
                throw new ValidationException("timeToLive must be between 1 millisecond and 3650 days.");
            }
        }
        if (maxMessageBytes <= 0 || maxMessageBytes > 16 * 1024 * 1024) {
            throw new ValidationException("maxMessageBytes must be between 1 and 16777216.");
        }
        if (maxDocumentBytes < maxMessageBytes || maxDocumentBytes > 64 * 1024 * 1024) {
            throw new ValidationException("maxDocumentBytes must be at least maxMessageBytes and at most 67108864.");
        }
    }

    /**
     * Creates conservative bounded defaults.
     *
     * @param client client options
     * @param partition tenant/isolation/agent context
     * @return default history options
     */
    public static ValkeyHistoryOptions defaults(ValkeyClientOptions client, ValkeyPartitionContext partition) {
        return new ValkeyHistoryOptions(
                client,
                partition,
                DEFAULT_PROVIDER_ID,
                DEFAULT_KEY_PREFIX,
                1000,
                100,
                null,
                1024 * 1024,
                8 * 1024 * 1024);
    }
}
