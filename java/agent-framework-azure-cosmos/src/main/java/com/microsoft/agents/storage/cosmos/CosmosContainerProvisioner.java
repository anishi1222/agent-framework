// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.IndexingPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import reactor.core.publisher.Mono;

final class CosmosContainerProvisioner {
    private CosmosContainerProvisioner() {}

    static CompletionStage<Void> provisionAsync(CosmosAsyncClient client, CosmosStorageOptions options) {
        Objects.requireNonNull(client, "client");
        CosmosProvisioningOptions provisioning = options.container().provisioning();
        if (!provisioning.enabled()) {
            return java.util.concurrent.CompletableFuture.completedStage(null);
        }
        String databaseId = options.container().databaseId();
        String containerId = options.container().containerId();
        CosmosContainerProperties desired =
                new CosmosContainerProperties(containerId, CosmosContainerOptions.PARTITION_KEY_PATH);
        desired.setIndexingPolicy(new IndexingPolicy().setAutomatic(provisioning.automaticIndexing()));
        desired.setDefaultTimeToLiveInSeconds(provisioning.defaultTimeToLiveSeconds());
        Mono<Void> operation = client.createDatabaseIfNotExists(databaseId)
                .then(client.getDatabase(databaseId).createContainerIfNotExists(desired))
                .then(client.getDatabase(databaseId).getContainer(containerId).read())
                .flatMap(response -> validate(response.getProperties(), provisioning))
                .then();
        return CosmosSdkSupport.stage(operation, options.client().retryOptions());
    }

    static CosmosAsyncContainer container(CosmosAsyncClient client, CosmosContainerOptions options) {
        return client.getDatabase(options.databaseId()).getContainer(options.containerId());
    }

    private static Mono<Void> validate(CosmosContainerProperties actual, CosmosProvisioningOptions expected) {
        List<String> paths = actual.getPartitionKeyDefinition().getPaths();
        if (!List.of(CosmosContainerOptions.PARTITION_KEY_PATH).equals(paths)) {
            return Mono.error(incompatible("Existing Cosmos container must use partition key path "
                    + CosmosContainerOptions.PARTITION_KEY_PATH
                    + "."));
        }
        if (!Objects.equals(actual.getIndexingPolicy().isAutomatic(), expected.automaticIndexing())) {
            return Mono.error(incompatible("Existing Cosmos container automatic-indexing policy is incompatible."));
        }
        if (!Objects.equals(actual.getDefaultTimeToLiveInSeconds(), expected.defaultTimeToLiveSeconds())) {
            return Mono.error(incompatible("Existing Cosmos container default TTL policy is incompatible."));
        }
        return Mono.empty();
    }

    private static CosmosStorageException incompatible(String message) {
        return new CosmosStorageException(message, null, CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE, null);
    }
}
