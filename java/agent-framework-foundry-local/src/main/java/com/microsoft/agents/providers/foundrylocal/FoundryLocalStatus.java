// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Represents process-neutral Foundry Local service status.
 *
 * @param endpoints reported HTTP endpoints
 * @param modelDirectory model cache directory, when reported
 * @param pipeName native service pipe name, when reported
 */
public record FoundryLocalStatus(List<URI> endpoints, String modelDirectory, String pipeName) {
    /** Creates a detached status value. */
    public FoundryLocalStatus {
        Objects.requireNonNull(endpoints, "endpoints");
        endpoints = List.copyOf(endpoints);
    }
}
