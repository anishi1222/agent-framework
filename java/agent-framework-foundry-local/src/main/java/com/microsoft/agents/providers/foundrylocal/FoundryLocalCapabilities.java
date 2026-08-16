// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

/**
 * Describes the stable Foundry Local REST capabilities used by this adapter.
 *
 * @param text text chat
 * @param streaming SSE chat
 * @param functionTools model-dependent function tools
 * @param structuredOutput model-dependent JSON output
 * @param modelDiscovery catalog and cached-model discovery
 * @param health service status discovery
 * @param nativeProcessManagement whether the adapter installs or starts native binaries
 */
public record FoundryLocalCapabilities(
        boolean text,
        boolean streaming,
        boolean functionTools,
        boolean structuredOutput,
        boolean modelDiscovery,
        boolean health,
        boolean nativeProcessManagement) {
    private static final FoundryLocalCapabilities CURRENT =
            new FoundryLocalCapabilities(true, true, true, true, true, true, false);

    /** Returns current capability flags. */
    public static FoundryLocalCapabilities current() {
        return CURRENT;
    }
}
