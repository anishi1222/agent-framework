// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

/**
 * Selects authentication for a Mistral-compatible endpoint.
 */
public enum MistralAuthenticationMode {
    /** Sends a bearer API key. */
    API_KEY,

    /** Sends no authorization header and is restricted to loopback endpoints. */
    NONE
}
