// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Configures session TTL and deletion behavior over shared Cosmos storage.
 *
 * @param storage shared client/container/partition/limit options
 * @param timeToLiveSeconds optional positive live-session TTL
 * @param deletePolicy soft tombstone or hard delete
 * @param tombstoneTimeToLiveSeconds positive soft-delete tombstone TTL
 */
public record CosmosSessionStoreOptions(
        CosmosStorageOptions storage,
        Integer timeToLiveSeconds,
        CosmosDeletePolicy deletePolicy,
        int tombstoneTimeToLiveSeconds) {
    /** Creates validated session-store options. */
    public CosmosSessionStoreOptions {
        storage = CosmosValidation.requireNonNull(storage, "storage");
        if (timeToLiveSeconds != null && timeToLiveSeconds <= 0) {
            throw new ValidationException("timeToLiveSeconds must be positive when present.");
        }
        deletePolicy = deletePolicy == null ? CosmosDeletePolicy.SOFT : deletePolicy;
        if (tombstoneTimeToLiveSeconds <= 0) {
            throw new ValidationException("tombstoneTimeToLiveSeconds must be greater than zero.");
        }
        if (storage.container().provisioning().enabled()
                && (timeToLiveSeconds != null || deletePolicy == CosmosDeletePolicy.SOFT)
                && storage.container().provisioning().defaultTimeToLiveSeconds() == null) {
            throw new ValidationException(
                    "Provisioned containers must enable TTL when item or tombstone TTL is configured.");
        }
    }
}
