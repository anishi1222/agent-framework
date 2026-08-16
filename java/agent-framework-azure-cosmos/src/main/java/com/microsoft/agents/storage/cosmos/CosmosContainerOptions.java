// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/**
 * Identifies a Cosmos database/container and its optional provisioning contract.
 *
 * @param databaseId database identifier
 * @param containerId container identifier
 * @param provisioning provisioning and validation policy
 */
public record CosmosContainerOptions(String databaseId, String containerId, CosmosProvisioningOptions provisioning) {
    /** Required partition-key path for every Agent Framework Cosmos container. */
    public static final String PARTITION_KEY_PATH = "/partitionKey";

    /** Creates validated container options. */
    public CosmosContainerOptions {
        databaseId = CosmosValidation.resourceId(databaseId, "databaseId");
        containerId = CosmosValidation.resourceId(containerId, "containerId");
        provisioning = provisioning == null ? CosmosProvisioningOptions.disabled() : provisioning;
    }
}
