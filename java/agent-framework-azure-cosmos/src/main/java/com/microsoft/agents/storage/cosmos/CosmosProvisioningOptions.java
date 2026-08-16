// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;

/**
 * Controls opt-in database/container creation and exact existing-container validation.
 *
 * @param enabled whether provisioning is enabled
 * @param automaticIndexing required automatic-indexing value
 * @param defaultTimeToLiveSeconds required container TTL, {@code -1} for item-level TTL, or
 *     {@code null} for disabled TTL
 */
public record CosmosProvisioningOptions(boolean enabled, boolean automaticIndexing, Integer defaultTimeToLiveSeconds) {
    /** Creates validated provisioning options. */
    public CosmosProvisioningOptions {
        if (defaultTimeToLiveSeconds != null && defaultTimeToLiveSeconds != -1 && defaultTimeToLiveSeconds <= 0) {
            throw new ValidationException("defaultTimeToLiveSeconds must be -1, positive, or null.");
        }
    }

    /**
     * Returns provisioning disabled.
     *
     * @return disabled options
     */
    public static CosmosProvisioningOptions disabled() {
        return new CosmosProvisioningOptions(false, true, null);
    }

    /**
     * Enables strict provisioning with item-level TTL support.
     *
     * @return enabled options
     */
    public static CosmosProvisioningOptions itemTimeToLive() {
        return new CosmosProvisioningOptions(true, true, -1);
    }
}
