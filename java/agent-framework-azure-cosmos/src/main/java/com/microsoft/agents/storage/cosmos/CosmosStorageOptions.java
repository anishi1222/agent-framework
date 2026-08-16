// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Contains shared bounded configuration for one Cosmos storage adapter.
 *
 * @param client client/authentication options
 * @param container database/container options
 * @param partition explicit tenant/isolation/agent context
 * @param maxDocumentBytes maximum serialized payload bytes
 * @param maxPageSize maximum documents read per page
 * @param maxConcurrentOperations maximum adapter fan-out
 */
public record CosmosStorageOptions(
        CosmosClientOptions client,
        CosmosContainerOptions container,
        CosmosPartitionContext partition,
        int maxDocumentBytes,
        int maxPageSize,
        int maxConcurrentOperations) {
    /** Maximum Cosmos item payload accepted by this adapter. */
    public static final int MAX_DOCUMENT_BYTES = 1_800_000;

    /** Creates validated bounded storage options. */
    public CosmosStorageOptions {
        client = CosmosValidation.requireNonNull(client, "client");
        container = CosmosValidation.requireNonNull(container, "container");
        partition = CosmosValidation.requireNonNull(partition, "partition");
        if (maxDocumentBytes <= 0 || maxDocumentBytes > MAX_DOCUMENT_BYTES) {
            throw new ValidationException("maxDocumentBytes must be between 1 and " + MAX_DOCUMENT_BYTES + ".");
        }
        if (maxPageSize <= 0 || maxPageSize > 1000) {
            throw new ValidationException("maxPageSize must be between 1 and 1000.");
        }
        if (maxConcurrentOperations <= 0 || maxConcurrentOperations > 256) {
            throw new ValidationException("maxConcurrentOperations must be between 1 and 256.");
        }
    }
}
