// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import java.util.List;
import java.util.Objects;

/**
 * Describes one Azure token request.
 *
 * @param scopes non-empty OAuth scopes
 * @param tenantId optional tenant identifier
 */
public record AzureTokenRequest(List<String> scopes, String tenantId) {
    /** Creates and defensively copies a token request. */
    public AzureTokenRequest {
        Objects.requireNonNull(scopes, "scopes");
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty.");
        }
        scopes = scopes.stream()
                .map(scope -> {
                    Objects.requireNonNull(scope, "scope");
                    if (scope.isBlank()) {
                        throw new IllegalArgumentException("scopes must not contain blank values.");
                    }
                    return scope;
                })
                .toList();
        if (tenantId != null && tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank.");
        }
    }

    /**
     * Creates a request without a tenant override.
     *
     * @param scopes OAuth scopes
     * @return token request
     */
    public static AzureTokenRequest forScopes(String... scopes) {
        return new AzureTokenRequest(List.of(scopes), null);
    }
}
