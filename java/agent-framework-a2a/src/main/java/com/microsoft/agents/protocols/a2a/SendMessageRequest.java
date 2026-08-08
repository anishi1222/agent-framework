// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Carries one A2A send request.
 *
 * @param message required message
 * @param configuration request configuration
 * @param metadata immutable request metadata
 * @param tenant optional tenant routing value
 */
public record SendMessageRequest(
        Message message, SendMessageConfiguration configuration, Map<String, StateValue> metadata, String tenant) {
    /** Creates a validated request. */
    public SendMessageRequest {
        message = Objects.requireNonNull(message, "message");
        configuration = configuration == null ? SendMessageConfiguration.defaults() : configuration;
        metadata = A2AValidation.metadata(metadata, "metadata");
        tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
    }

    /**
     * Creates a request with defaults.
     *
     * @param message required message
     */
    public SendMessageRequest(Message message) {
        this(message, SendMessageConfiguration.defaults(), Map.of(), null);
    }
}
