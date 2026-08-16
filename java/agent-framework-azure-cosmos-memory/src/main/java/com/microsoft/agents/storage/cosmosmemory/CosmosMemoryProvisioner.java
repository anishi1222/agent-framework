// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CompositePath;
import com.azure.cosmos.models.CompositePathSortOrder;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosFullTextIndex;
import com.azure.cosmos.models.CosmosFullTextPath;
import com.azure.cosmos.models.CosmosFullTextPolicy;
import com.azure.cosmos.models.CosmosVectorEmbedding;
import com.azure.cosmos.models.CosmosVectorEmbeddingPolicy;
import com.azure.cosmos.models.CosmosVectorIndexSpec;
import com.azure.cosmos.models.IndexingPolicy;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.storage.cosmos.CosmosContainerOptions;
import com.microsoft.agents.storage.cosmos.CosmosProvisioningOptions;
import com.microsoft.agents.storage.cosmos.CosmosStorageException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import reactor.core.publisher.Mono;

final class CosmosMemoryProvisioner {
    private CosmosMemoryProvisioner() {}

    static CompletionStage<Void> provisionAsync(CosmosAsyncClient client, CosmosMemoryOptions options) {
        CosmosProvisioningOptions provisioning = options.storage().container().provisioning();
        String databaseId = options.storage().container().databaseId();
        String containerId = options.storage().container().containerId();
        CosmosContainerProperties desired = desiredContainer(containerId, options);
        Mono<Void> operation = provisioning.enabled()
                ? client.createDatabaseIfNotExists(databaseId)
                        .then(client.getDatabase(databaseId).createContainerIfNotExists(desired))
                        .then(client.getDatabase(databaseId)
                                .getContainer(containerId)
                                .read())
                        .flatMap(response -> validate(response.getProperties(), options))
                        .then()
                : client.getDatabase(databaseId)
                        .getContainer(containerId)
                        .read()
                        .flatMap(response -> validate(response.getProperties(), options))
                        .then();
        return CosmosMemorySdkSupport.stage(
                operation, options.storage().client().retryOptions(), new DefaultRunCancellation());
    }

    static CosmosAsyncContainer container(CosmosAsyncClient client, CosmosMemoryOptions options) {
        return client.getDatabase(options.storage().container().databaseId())
                .getContainer(options.storage().container().containerId());
    }

    static CosmosContainerProperties desiredContainer(String containerId, CosmosMemoryOptions options) {
        CosmosContainerProperties properties =
                new CosmosContainerProperties(containerId, CosmosContainerOptions.PARTITION_KEY_PATH);
        CosmosVectorEmbedding embedding = new CosmosVectorEmbedding()
                .setPath(CosmosMemoryOptions.VECTOR_PATH)
                .setDataType(com.azure.cosmos.models.CosmosVectorDataType.valueOf(
                        options.vector().dataType().name()))
                .setEmbeddingDimensions(options.vector().dimensions())
                .setDistanceFunction(com.azure.cosmos.models.CosmosVectorDistanceFunction.valueOf(
                        options.vector().distance().name()));
        CosmosVectorEmbeddingPolicy vectorPolicy = new CosmosVectorEmbeddingPolicy();
        vectorPolicy.setCosmosVectorEmbeddings(List.of(embedding));
        properties.setVectorEmbeddingPolicy(vectorPolicy);

        IndexingPolicy indexing = new IndexingPolicy()
                .setAutomatic(options.storage().container().provisioning().automaticIndexing())
                .setCompositeIndexes(requiredCompositeIndexes())
                .setVectorIndexes(List.of(new CosmosVectorIndexSpec()
                        .setPath(CosmosMemoryOptions.VECTOR_PATH)
                        .setType(options.vector().indexType().value())));
        if (options.fullTextEnabled()) {
            CosmosFullTextPolicy fullText = new CosmosFullTextPolicy()
                    .setDefaultLanguage(options.fullTextLanguage())
                    .setPaths(List.of(new CosmosFullTextPath()
                            .setPath(CosmosMemoryOptions.FULL_TEXT_PATH)
                            .setLanguage(options.fullTextLanguage())));
            properties.setFullTextPolicy(fullText);
            indexing.setCosmosFullTextIndexes(
                    List.of(new CosmosFullTextIndex().setPath(CosmosMemoryOptions.FULL_TEXT_PATH)));
        }
        properties.setIndexingPolicy(indexing);
        properties.setDefaultTimeToLiveInSeconds(
                options.storage().container().provisioning().defaultTimeToLiveSeconds());
        return properties;
    }

    static Mono<Void> validate(CosmosContainerProperties actual, CosmosMemoryOptions options) {
        if (!List.of(CosmosContainerOptions.PARTITION_KEY_PATH)
                .equals(actual.getPartitionKeyDefinition().getPaths())) {
            return Mono.error(incompatible("Existing Cosmos memory container must use /partitionKey."));
        }
        if (actual.getIndexingPolicy() == null) {
            return Mono.error(incompatible("Existing Cosmos memory container has no effective indexing policy."));
        }
        if (!Objects.equals(
                actual.getIndexingPolicy().isAutomatic(),
                options.storage().container().provisioning().automaticIndexing())) {
            return Mono.error(incompatible("Existing Cosmos memory automatic-indexing policy is incompatible."));
        }
        if (!timeToLivePolicyMatches(actual, options)) {
            return Mono.error(incompatible("Existing Cosmos memory TTL policy is incompatible."));
        }
        if (!vectorPolicyMatches(actual, options) || !vectorIndexMatches(actual, options)) {
            return Mono.error(incompatible(
                    "Existing Cosmos memory vector policy/index is incompatible; vector policies are immutable."));
        }
        if (!compositeIndexMatches(actual)) {
            return Mono.error(incompatible("Existing Cosmos memory container requires composite index "
                    + "(/updatedAt DESC, /id ASC) for stable list pagination."));
        }
        if (!fullTextPolicyMatches(actual, options)) {
            return Mono.error(incompatible("Existing Cosmos memory full-text policy/index is incompatible."));
        }
        return Mono.empty();
    }

    static List<List<CompositePath>> requiredCompositeIndexes() {
        return List.of(List.of(
                new CompositePath().setPath("/updatedAt").setOrder(CompositePathSortOrder.DESCENDING),
                new CompositePath().setPath("/id").setOrder(CompositePathSortOrder.ASCENDING)));
    }

    private static boolean compositeIndexMatches(CosmosContainerProperties actual) {
        List<List<CompositePath>> indexes = actual.getIndexingPolicy().getCompositeIndexes();
        if (indexes == null) {
            return false;
        }
        return indexes.stream()
                .anyMatch(index -> index != null
                        && index.size() == 2
                        && index.get(0) != null
                        && index.get(1) != null
                        && "/updatedAt".equals(index.get(0).getPath())
                        && "/id".equals(index.get(1).getPath())
                        && ((compositeOrderMatches(
                                        index, CompositePathSortOrder.DESCENDING, CompositePathSortOrder.ASCENDING))
                                || compositeOrderMatches(
                                        index, CompositePathSortOrder.ASCENDING, CompositePathSortOrder.DESCENDING)));
    }

    private static boolean timeToLivePolicyMatches(CosmosContainerProperties actual, CosmosMemoryOptions options) {
        Integer actualTimeToLive = actual.getDefaultTimeToLiveInSeconds();
        CosmosProvisioningOptions provisioning = options.storage().container().provisioning();
        if (provisioning.enabled()) {
            return Objects.equals(actualTimeToLive, provisioning.defaultTimeToLiveSeconds());
        }
        if (options.timeToLiveSeconds() != null) {
            return actualTimeToLive != null && (actualTimeToLive == -1 || actualTimeToLive > 0);
        }
        return actualTimeToLive == null || actualTimeToLive == -1;
    }

    private static boolean compositeOrderMatches(
            List<CompositePath> index, CompositePathSortOrder first, CompositePathSortOrder second) {
        return first == index.get(0).getOrder() && second == index.get(1).getOrder();
    }

    private static boolean vectorPolicyMatches(CosmosContainerProperties actual, CosmosMemoryOptions options) {
        CosmosVectorEmbeddingPolicy policy = actual.getVectorEmbeddingPolicy();
        if (policy == null
                || policy.getVectorEmbeddings() == null
                || policy.getVectorEmbeddings().size() != 1) {
            return false;
        }
        CosmosVectorEmbedding embedding = policy.getVectorEmbeddings().getFirst();
        return CosmosMemoryOptions.VECTOR_PATH.equals(embedding.getPath())
                && Objects.equals(
                        embedding.getEmbeddingDimensions(), options.vector().dimensions())
                && embedding
                        .getDataType()
                        .name()
                        .equals(options.vector().dataType().name())
                && embedding
                        .getDistanceFunction()
                        .name()
                        .equals(options.vector().distance().name());
    }

    private static boolean vectorIndexMatches(CosmosContainerProperties actual, CosmosMemoryOptions options) {
        List<CosmosVectorIndexSpec> indexes = actual.getIndexingPolicy().getVectorIndexes();
        return indexes != null
                && indexes.size() == 1
                && CosmosMemoryOptions.VECTOR_PATH.equals(indexes.getFirst().getPath())
                && options.vector()
                        .indexType()
                        .value()
                        .equals(indexes.getFirst().getType());
    }

    private static boolean fullTextPolicyMatches(CosmosContainerProperties actual, CosmosMemoryOptions options) {
        CosmosFullTextPolicy policy = actual.getFullTextPolicy();
        List<CosmosFullTextIndex> indexes = actual.getIndexingPolicy().getCosmosFullTextIndexes();
        if (!options.fullTextEnabled()) {
            return policy == null && (indexes == null || indexes.isEmpty());
        }
        return policy != null
                && options.fullTextLanguage().equals(policy.getDefaultLanguage())
                && policy.getPaths() != null
                && policy.getPaths().size() == 1
                && CosmosMemoryOptions.FULL_TEXT_PATH.equals(
                        policy.getPaths().getFirst().getPath())
                && options.fullTextLanguage()
                        .equals(policy.getPaths().getFirst().getLanguage())
                && indexes != null
                && indexes.size() == 1
                && CosmosMemoryOptions.FULL_TEXT_PATH.equals(indexes.getFirst().getPath());
    }

    private static CosmosStorageException incompatible(String message) {
        return new CosmosStorageException(message, null, CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE, null);
    }
}
