// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import java.time.Instant;
import java.util.Objects;

/**
 * Holds one expiring Entra access token for the Power Platform audience.
 */
public final class CopilotStudioAccessToken {
    private final String token;

    private final Instant expiresAt;

    /**
     * Creates a redacting access-token value.
     *
     * @param token bearer token without a scheme prefix
     * @param expiresAt expiration instant
     */
    public CopilotStudioAccessToken(String token, Instant expiresAt) {
        this.token = Objects.requireNonNull(token, "token");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (token.isBlank() || token.indexOf('\0') >= 0 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("token must not be blank or contain control delimiters.");
        }
    }

    /**
     * Returns the expiration instant.
     *
     * @return expiration instant
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    String reveal() {
        return token;
    }

    @Override
    public String toString() {
        return "CopilotStudioAccessToken{token=[REDACTED], expiresAt=" + expiresAt + '}';
    }
}
