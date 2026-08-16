// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a sanitized stable hosting error.
 *
 * @param code stable error code
 * @param message safe client-facing message
 * @param retryable whether retry may succeed without changing the request
 * @param details redacted additive details
 */
public record HostingError(HostingErrorCode code, String message, boolean retryable, Map<String, StateValue> details) {
    /** Creates a validated immutable error. */
    public HostingError {
        Objects.requireNonNull(code, "code");
        message = HostingValidation.nonBlank(message, "message");
        Objects.requireNonNull(details, "details");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        details.forEach((key, value) -> copy.put(
                HostingValidation.nonBlank(key, "details key"),
                HostingRedactor.redact(Objects.requireNonNull(value, "details value"))));
        details = Map.copyOf(copy);
    }

    /**
     * Creates an error without details.
     *
     * @param code stable code
     * @param message safe message
     * @return error
     */
    public static HostingError of(HostingErrorCode code, String message) {
        return new HostingError(code, message, false, Map.of());
    }
}
