// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/**
 * Represents an authorization decision.
 *
 * @param allowed whether the operation is allowed
 * @param policyId optional stable policy identifier for server-side correlation
 */
public record HostingAuthorizationDecision(boolean allowed, String policyId) {
    /** Creates a validated decision. */
    public HostingAuthorizationDecision {
        policyId = HostingValidation.optionalNonBlank(policyId, "policyId");
    }

    /**
     * Creates an allow decision.
     *
     * @return allow decision
     */
    public static HostingAuthorizationDecision allow() {
        return new HostingAuthorizationDecision(true, null);
    }

    /**
     * Creates a deny decision.
     *
     * @param policyId optional stable policy identifier
     * @return deny decision
     */
    public static HostingAuthorizationDecision deny(String policyId) {
        return new HostingAuthorizationDecision(false, policyId);
    }
}
