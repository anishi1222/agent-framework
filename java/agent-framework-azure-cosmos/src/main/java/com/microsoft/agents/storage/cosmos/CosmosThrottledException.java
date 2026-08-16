// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/** Reports a 429 that remained after the configured bounded SDK retry policy. */
public final class CosmosThrottledException extends CosmosStorageException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a sanitized throttling exception.
     *
     * @param cause internal cause
     * @param diagnostics request units, activity ID, and retry delay
     */
    public CosmosThrottledException(Throwable cause, CosmosOperationDiagnostics diagnostics) {
        super("Cosmos DB throttled the operation after bounded retries.", cause, Kind.SERVICE, diagnostics);
    }
}
