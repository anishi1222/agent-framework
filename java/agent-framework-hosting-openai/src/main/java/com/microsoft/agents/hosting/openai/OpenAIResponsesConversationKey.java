// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

/**
 * Identifies retained transcript state by trusted identity, route, reference family, and caller id.
 *
 * @param principalId authenticated principal identifier
 * @param isolationId independently derived confidentiality partition
 * @param routeId generic hosted-agent route identifier
 * @param referenceType mutable-conversation or immutable-response family
 * @param referenceId untrusted caller correlation identifier within the trusted partition
 */
public record OpenAIResponsesConversationKey(
        String principalId,
        String isolationId,
        String routeId,
        OpenAIResponsesReferenceType referenceType,
        String referenceId) {
    /** Creates a validated immutable key. */
    public OpenAIResponsesConversationKey {
        principalId = requireNonBlank(principalId, "principalId");
        isolationId = requireNonBlank(isolationId, "isolationId");
        routeId = requireNonBlank(routeId, "routeId");
        java.util.Objects.requireNonNull(referenceType, "referenceType");
        referenceId = requireNonBlank(referenceId, "referenceId");
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
