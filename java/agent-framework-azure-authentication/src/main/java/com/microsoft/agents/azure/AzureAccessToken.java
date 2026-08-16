// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import java.time.Instant;
import java.util.Objects;

/**
 * Holds an Azure access token and expiry without rendering the credential in diagnostics.
 *
 * @param token non-blank bearer token
 * @param expiresAt token expiry
 */
public record AzureAccessToken(String token, Instant expiresAt) {
    /** Creates and validates an access token. */
    public AzureAccessToken {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank.");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "AzureAccessToken[token=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
