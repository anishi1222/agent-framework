// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/** Selects the Cosmos SDK connectivity mode. */
public enum CosmosConnectionMode {
    /** Uses the SDK direct-mode data path and gateway control plane. */
    DIRECT,
    /** Uses the SDK HTTPS gateway path, primarily for restricted networks and tests. */
    GATEWAY
}
