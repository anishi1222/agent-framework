// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Configures bounded ordered Cosmos history persistence.
 *
 * @param storage shared storage options
 * @param providerId stable history-provider identifier
 * @param timeToLiveSeconds optional positive message TTL
 * @param maxMessagesToLoad maximum messages returned by the HistoryProvider SPI
 * @param pageSize maximum query page size
 * @param maxAppendBatchSize maximum atomic append size, at most 99 plus the sequence head
 * @param maxConcurrencyRetries maximum ETag-head retries
 */
public record CosmosHistoryOptions(
        CosmosStorageOptions storage,
        String providerId,
        Integer timeToLiveSeconds,
        int maxMessagesToLoad,
        int pageSize,
        int maxAppendBatchSize,
        int maxConcurrencyRetries) {
    /** Creates validated bounded history options. */
    public CosmosHistoryOptions {
        storage = CosmosValidation.requireNonNull(storage, "storage");
        providerId = CosmosValidation.requireNonBlank(providerId, "providerId");
        if (timeToLiveSeconds != null && timeToLiveSeconds <= 0) {
            throw new ValidationException("timeToLiveSeconds must be positive when present.");
        }
        if (maxMessagesToLoad <= 0 || maxMessagesToLoad > 10_000) {
            throw new ValidationException("maxMessagesToLoad must be between 1 and 10000.");
        }
        if (pageSize <= 0 || pageSize > storage.maxPageSize()) {
            throw new ValidationException("pageSize must be positive and not exceed storage.maxPageSize.");
        }
        if (maxAppendBatchSize <= 0 || maxAppendBatchSize > 99) {
            throw new ValidationException("maxAppendBatchSize must be between 1 and 99.");
        }
        if (maxConcurrencyRetries < 0 || maxConcurrencyRetries > 20) {
            throw new ValidationException("maxConcurrencyRetries must be between 0 and 20.");
        }
        if (storage.container().provisioning().enabled()
                && timeToLiveSeconds != null
                && storage.container().provisioning().defaultTimeToLiveSeconds() == null) {
            throw new ValidationException("Provisioned containers must enable TTL when message TTL is configured.");
        }
    }
}
