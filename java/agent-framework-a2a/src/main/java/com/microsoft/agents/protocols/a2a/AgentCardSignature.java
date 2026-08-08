// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Carries a JWS signature over an agent card.
 *
 * @param protectedHeader encoded protected header
 * @param signature encoded signature
 * @param header immutable unprotected header metadata
 */
public record AgentCardSignature(String protectedHeader, String signature, Map<String, StateValue> header) {
    /** Creates a validated signature value. */
    public AgentCardSignature {
        protectedHeader = A2AValidation.nonBlank(protectedHeader, "protectedHeader");
        signature = A2AValidation.nonBlank(signature, "signature");
        header = A2AValidation.metadata(header, "header");
    }
}
