// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Configures one workflow-bound Cosmos checkpoint store.
 *
 * @param storage shared storage options
 * @param workflowId exact workflow identity bound to the logical partition
 * @param timeToLiveSeconds optional positive checkpoint TTL
 * @param pageSize bounded checkpoint-list page size
 */
public record CosmosCheckpointOptions(
        CosmosStorageOptions storage, String workflowId, Integer timeToLiveSeconds, int pageSize) {
    /** Maximum invocation-ledger mutations in one atomic checkpoint commit. */
    public static final int MAX_LEDGER_MUTATIONS = 98;

    /** Creates validated checkpoint options. */
    public CosmosCheckpointOptions {
        storage = CosmosValidation.requireNonNull(storage, "storage");
        workflowId = CosmosValidation.requireNonBlank(workflowId, "workflowId");
        if (timeToLiveSeconds != null && timeToLiveSeconds <= 0) {
            throw new ValidationException("timeToLiveSeconds must be positive when present.");
        }
        if (pageSize <= 0 || pageSize > storage.maxPageSize()) {
            throw new ValidationException("pageSize must be positive and not exceed storage.maxPageSize.");
        }
        if (storage.container().provisioning().enabled()
                && timeToLiveSeconds != null
                && storage.container().provisioning().defaultTimeToLiveSeconds() == null) {
            throw new ValidationException("Provisioned containers must enable TTL when checkpoint TTL is configured.");
        }
    }
}
