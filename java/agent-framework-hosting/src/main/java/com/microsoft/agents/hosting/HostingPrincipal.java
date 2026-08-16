// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.Map;

/**
 * Represents authenticated hosting identity and its independently derived isolation partition.
 *
 * <p>Run, thread, session, response, and continuation identifiers are never authorization
 * authorities. A transport must derive this value only from a trusted authentication boundary.
 *
 * @param principalId stable authenticated principal identifier
 * @param isolationId stable tenant or confidentiality partition identifier
 * @param claims immutable trusted claims safe to expose to hosting policy
 */
public record HostingPrincipal(String principalId, String isolationId, Map<String, String> claims) {
    /** Creates a validated immutable principal. */
    public HostingPrincipal {
        principalId = HostingValidation.nonBlank(principalId, "principalId");
        isolationId = HostingValidation.nonBlank(isolationId, "isolationId");
        claims = HostingValidation.copyStrings(claims, "claims");
    }

    /**
     * Creates a principal without additive claims.
     *
     * @param principalId principal identifier
     * @param isolationId isolation identifier
     */
    public HostingPrincipal(String principalId, String isolationId) {
        this(principalId, isolationId, Map.of());
    }
}
