// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Defines finite client and hosting limits used at the MCP boundary.
 *
 * @param maxPayloadBytes maximum decoded payload or content bytes
 * @param maxNestingDepth maximum JSON-shaped value depth
 * @param maxCollectionItems maximum members in one collection and aggregate page result
 * @param maxPages maximum pages followed by aggregate list operations
 * @param maxConcurrentRequests maximum in-flight operations
 * @param maxEventBuffer maximum buffered client notifications
 */
public record MCPLimits(
        int maxPayloadBytes,
        int maxNestingDepth,
        int maxCollectionItems,
        int maxPages,
        int maxConcurrentRequests,
        int maxEventBuffer) {
    /** Creates validated finite limits. */
    public MCPLimits {
        MCPValidation.positive(maxPayloadBytes, "maxPayloadBytes");
        MCPValidation.positive(maxNestingDepth, "maxNestingDepth");
        MCPValidation.positive(maxCollectionItems, "maxCollectionItems");
        MCPValidation.positive(maxPages, "maxPages");
        MCPValidation.positive(maxConcurrentRequests, "maxConcurrentRequests");
        MCPValidation.positive(maxEventBuffer, "maxEventBuffer");
    }

    /**
     * Returns conservative defaults for untrusted MCP peers.
     *
     * @return immutable default limits
     */
    public static MCPLimits defaults() {
        return new MCPLimits(1_048_576, 32, 10_000, 100, 32, 256);
    }
}
