// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.CompositePath;
import com.azure.cosmos.models.CompositePathSortOrder;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosContainerResponse;
import com.azure.cosmos.models.CosmosDatabaseResponse;
import com.azure.cosmos.models.IndexingPolicy;
import com.microsoft.agents.storage.cosmos.CosmosAccountKey;
import com.microsoft.agents.storage.cosmos.CosmosAuthentication;
import com.microsoft.agents.storage.cosmos.CosmosClientOptions;
import com.microsoft.agents.storage.cosmos.CosmosConnectionMode;
import com.microsoft.agents.storage.cosmos.CosmosContainerOptions;
import com.microsoft.agents.storage.cosmos.CosmosEndpoint;
import com.microsoft.agents.storage.cosmos.CosmosPartitionContext;
import com.microsoft.agents.storage.cosmos.CosmosProvisioningOptions;
import com.microsoft.agents.storage.cosmos.CosmosRetryOptions;
import com.microsoft.agents.storage.cosmos.CosmosStorageException;
import com.microsoft.agents.storage.cosmos.CosmosStorageOptions;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

class CosmosMemoryProvisionerTest {
    @Test
    void desiredPolicy_shouldUseRealSdkCompositePathsAndSerializeExactDirections() {
        // Arrange
        CosmosMemoryOptions options = options(true);

        // Act
        CosmosContainerProperties properties = CosmosMemoryProvisioner.desiredContainer("items", options);
        List<CompositePath> composite =
                properties.getIndexingPolicy().getCompositeIndexes().getFirst();
        String serialized = sdkJson(properties.getIndexingPolicy());

        // Assert
        assertThat(composite).extracting(CompositePath::getPath).containsExactly("/updatedAt", "/id");
        assertThat(composite)
                .extracting(CompositePath::getOrder)
                .containsExactly(CompositePathSortOrder.DESCENDING, CompositePathSortOrder.ASCENDING);
        assertThat(serialized)
                .contains("\"compositeIndexes\"")
                .contains("\"path\":\"/updatedAt\"")
                .contains("\"order\":\"descending\"")
                .contains("\"path\":\"/id\"")
                .contains("\"order\":\"ascending\"");
    }

    @Test
    void provisioning_shouldCreateWithExactPolicyThenValidateEffectiveSdkPolicy() {
        // Arrange
        CosmosMemoryOptions options = options(true);
        CosmosAsyncClient client = mock(CosmosAsyncClient.class);
        CosmosAsyncDatabase database = mock(CosmosAsyncDatabase.class);
        CosmosAsyncContainer container = mock(CosmosAsyncContainer.class);
        CosmosDatabaseResponse databaseResponse = mock(CosmosDatabaseResponse.class);
        CosmosContainerResponse createResponse = mock(CosmosContainerResponse.class);
        CosmosContainerResponse readResponse = mock(CosmosContainerResponse.class);
        ArgumentCaptor<CosmosContainerProperties> created = ArgumentCaptor.forClass(CosmosContainerProperties.class);
        when(client.createDatabaseIfNotExists("db")).thenReturn(Mono.just(databaseResponse));
        when(client.getDatabase("db")).thenReturn(database);
        when(database.createContainerIfNotExists(created.capture())).thenReturn(Mono.just(createResponse));
        when(database.getContainer("items")).thenReturn(container);
        when(container.read()).thenReturn(Mono.just(readResponse));
        when(readResponse.getProperties()).thenReturn(CosmosMemoryProvisioner.desiredContainer("items", options));

        // Act
        CosmosMemoryProvisioner.provisionAsync(client, options)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(created.getValue().getIndexingPolicy().getCompositeIndexes())
                .containsExactly(
                        CosmosMemoryProvisioner.requiredCompositeIndexes().getFirst());
        verify(container).read();
    }

    @Test
    void byoValidation_shouldAcceptExactOrGloballyReversedCompositeDirections() {
        // Arrange
        CosmosMemoryOptions options = options(false);
        CosmosContainerProperties exact = CosmosMemoryProvisioner.desiredContainer("items", options);
        CosmosContainerProperties reversed = CosmosMemoryProvisioner.desiredContainer("items", options);
        reversed.getIndexingPolicy()
                .setCompositeIndexes(List.of(List.of(
                        new CompositePath().setPath("/updatedAt").setOrder(CompositePathSortOrder.ASCENDING),
                        new CompositePath().setPath("/id").setOrder(CompositePathSortOrder.DESCENDING))));

        // Act / Assert
        CosmosMemoryProvisioner.validate(exact, options).block();
        CosmosMemoryProvisioner.validate(reversed, options).block();
    }

    @Test
    void byoValidation_shouldRejectMixedCompositeDirectionsWithoutMutatingContainer() {
        // Arrange
        CosmosMemoryOptions options = options(false);
        CosmosContainerProperties incompatible = CosmosMemoryProvisioner.desiredContainer("items", options);
        incompatible
                .getIndexingPolicy()
                .setCompositeIndexes(List.of(List.of(
                        new CompositePath().setPath("/updatedAt").setOrder(CompositePathSortOrder.ASCENDING),
                        new CompositePath().setPath("/id").setOrder(CompositePathSortOrder.ASCENDING))));
        CosmosAsyncClient client = mock(CosmosAsyncClient.class);
        CosmosAsyncDatabase database = mock(CosmosAsyncDatabase.class);
        CosmosAsyncContainer container = mock(CosmosAsyncContainer.class);
        CosmosContainerResponse readResponse = mock(CosmosContainerResponse.class);
        when(client.getDatabase("db")).thenReturn(database);
        when(database.getContainer("items")).thenReturn(container);
        when(container.read()).thenReturn(Mono.just(readResponse));
        when(readResponse.getProperties()).thenReturn(incompatible);

        // Act / Assert
        assertThatThrownBy(() -> CosmosMemoryProvisioner.provisionAsync(client, options)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CosmosStorageException.class)
                .hasRootCauseMessage("Existing Cosmos memory container requires composite index "
                        + "(/updatedAt DESC, /id ASC) for stable list pagination.");
        verify(client, never()).createDatabaseIfNotExists(anyString());
        verify(database, never()).createContainerIfNotExists(any(CosmosContainerProperties.class));
        verify(container, never()).replace(any(CosmosContainerProperties.class));
    }

    private static CosmosMemoryOptions options(boolean provisioning) {
        CosmosStorageOptions storage = new CosmosStorageOptions(
                new CosmosClientOptions(
                        CosmosEndpoint.parse("https://account.documents.azure.com/"),
                        CosmosAuthentication.accountKey(CosmosAccountKey.of("test-key")),
                        new CosmosRetryOptions(1, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        CosmosConnectionMode.GATEWAY,
                        "agent-framework-test"),
                new CosmosContainerOptions(
                        "db",
                        "items",
                        provisioning
                                ? CosmosProvisioningOptions.itemTimeToLive()
                                : CosmosProvisioningOptions.disabled()),
                new CosmosPartitionContext("tenant", "principal", "agent"),
                1_800_000,
                100,
                8);
        return new CosmosMemoryOptions(
                storage,
                new CosmosMemoryVectorOptions(
                        3, CosmosVectorDataType.FLOAT32, CosmosVectorDistance.COSINE, CosmosVectorIndexType.FLAT),
                true,
                "en-US",
                provisioning ? 3600 : null,
                25,
                8,
                8,
                CosmosMemoryFallback.DISABLED,
                100);
    }

    private static String sdkJson(IndexingPolicy policy) {
        try {
            Method populate = IndexingPolicy.class.getDeclaredMethod("populatePropertyBag");
            populate.setAccessible(true);
            populate.invoke(policy);
            Method getJson = IndexingPolicy.class.getDeclaredMethod("getJsonSerializable");
            getJson.setAccessible(true);
            Object serializable = getJson.invoke(policy);
            return (String) serializable.getClass().getMethod("toJson").invoke(serializable);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
