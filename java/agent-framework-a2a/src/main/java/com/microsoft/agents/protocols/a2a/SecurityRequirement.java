// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Defines AND-related schemes for one alternative security requirement.
 *
 * @param schemes scheme names mapped to required scopes
 */
public record SecurityRequirement(Map<String, List<String>> schemes) {
    /** Creates a validated immutable requirement. */
    public SecurityRequirement {
        TreeMap<String, List<String>> copy = new TreeMap<>();
        A2AValidation.map(schemes, "schemes")
                .forEach((name, scopes) -> copy.put(name, A2AValidation.strings(scopes, "scopes", true)));
        schemes = java.util.Collections.unmodifiableMap(copy);
        if (schemes.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException("schemes must not be empty.");
        }
    }

    /**
     * Creates a one-scheme requirement.
     *
     * @param name registered scheme name
     * @param scopes required scopes
     * @return requirement
     */
    public static SecurityRequirement of(String name, List<String> scopes) {
        return new SecurityRequirement(Map.of(name, scopes));
    }
}
