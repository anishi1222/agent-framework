// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

/** Identifies the configured Microsoft Foundry invocation surface. */
public enum FoundrySurface {
    /** Direct project Responses invocation using a model deployment. */
    MODEL,
    /** Invocation of an existing versioned Foundry agent reference. */
    AGENT
}
