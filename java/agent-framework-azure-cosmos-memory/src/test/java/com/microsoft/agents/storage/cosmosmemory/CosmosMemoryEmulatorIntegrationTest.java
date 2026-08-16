// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.agents.memory.MemoryFilter;
import com.microsoft.agents.agents.memory.MemoryKey;
import com.microsoft.agents.agents.memory.MemoryListRequest;
import com.microsoft.agents.agents.memory.MemoryMetadata;
import com.microsoft.agents.agents.memory.MemoryPage;
import com.microsoft.agents.agents.memory.MemoryQuery;
import com.microsoft.agents.agents.memory.MemoryRecord;
import com.microsoft.agents.agents.memory.MemoryScope;
import com.microsoft.agents.agents.memory.MemorySearchMode;
import com.microsoft.agents.agents.memory.MemorySearchResult;
import com.microsoft.agents.core.DefaultRunCancellation;
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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Opt-in local-emulator memory coverage.
 *
 * <p>Set {@code COSMOS_EMULATOR_TESTS=true}, {@code COSMOS_EMULATOR_ENDPOINT} to a loopback HTTPS
 * endpoint, and {@code COSMOS_EMULATOR_KEY} to the local emulator key. The Java trust store must
 * trust the emulator certificate. The current Linux vNext emulator documents custom indexing
 * policy as a no-op and doesn't advertise vector/full-text support; those limitations abort the
 * affected test rather than count as passing feature proof. Always-on contract tests separately
 * validate the exact legal SQL and SDK policy shape.
 */
class CosmosMemoryEmulatorIntegrationTest {
    private static final MemoryScope SCOPE = new MemoryScope("tenant", "scope");

    @Test
    void emulator_shouldExecuteStableMemoryListWhenEffectiveCompositePolicyIsSupported() {
        EmulatorEnvironment environment = EmulatorEnvironment.require();
        try (Fixture fixture = Fixture.create(environment, false)) {
            fixture.store
                    .putAsync(record("memory-1", "first", List.of(1.0, 0.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();
            fixture.store
                    .putAsync(record("memory-2", "second", List.of(0.0, 1.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();

            MemoryPage<com.microsoft.agents.core.VersionedSnapshot<MemoryRecord>> first = fixture.store
                    .listAsync(new MemoryListRequest(SCOPE, MemoryFilter.none(), 1, null), cancellation())
                    .toCompletableFuture()
                    .join();
            MemoryPage<com.microsoft.agents.core.VersionedSnapshot<MemoryRecord>> second = fixture.store
                    .listAsync(new MemoryListRequest(SCOPE, MemoryFilter.none(), 1, first.cursor()), cancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(first.items()).hasSize(1);
            assertThat(first.cursor()).isNotNull();
            assertThat(second.items()).hasSize(1);
            assertThat(List.of(
                            first.items().getFirst().snapshot().key().memoryId(),
                            second.items().getFirst().snapshot().key().memoryId()))
                    .containsExactlyInAnyOrder("memory-1", "memory-2");
        }
    }

    @Test
    void emulator_shouldExecuteVectorQueryWhenFeatureIsSupported() {
        EmulatorEnvironment environment = EmulatorEnvironment.require();
        try (Fixture fixture = Fixture.create(environment, false)) {
            fixture.store
                    .putAsync(record("near", "near", List.of(1.0, 0.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();
            fixture.store
                    .putAsync(record("far", "far", List.of(0.0, 1.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();
            MemoryQuery query = new MemoryQuery(
                    SCOPE,
                    null,
                    new EmbeddingVector(List.of(1.0, 0.0, 0.0)),
                    MemoryFilter.none(),
                    MemorySearchMode.VECTOR,
                    2);

            MemoryPage<MemorySearchResult> page = searchOrAbort(fixture.store, query, "vector search");

            assertThat(page.cursor()).isNull();
            assertThat(page.items()).isNotEmpty().allSatisfy(result -> {
                assertThat(result.hasScore()).isTrue();
                assertThat(result.optionalScore()).isPresent();
                assertThat(result.rank()).isPositive();
            });
        }
    }

    @Test
    void emulator_shouldExecuteFullTextAndHybridQueriesWhenFeaturesAreSupported() {
        EmulatorEnvironment environment = EmulatorEnvironment.require();
        try (Fixture fixture = Fixture.create(environment, true)) {
            fixture.store
                    .putAsync(record("hiking", "vegetarian hiking preferences", List.of(1.0, 0.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();
            fixture.store
                    .putAsync(record("other", "unrelated note", List.of(0.0, 1.0, 0.0)), cancellation())
                    .toCompletableFuture()
                    .join();
            MemoryQuery fullText =
                    new MemoryQuery(SCOPE, "hiking", null, MemoryFilter.none(), MemorySearchMode.FULL_TEXT, 2);
            MemoryQuery hybrid = new MemoryQuery(
                    SCOPE,
                    "hiking",
                    new EmbeddingVector(List.of(1.0, 0.0, 0.0)),
                    MemoryFilter.none(),
                    MemorySearchMode.HYBRID,
                    2);

            MemoryPage<MemorySearchResult> fullTextPage = searchOrAbort(fixture.store, fullText, "full-text search");
            MemoryPage<MemorySearchResult> hybridPage = searchOrAbort(fixture.store, hybrid, "hybrid search");

            assertThat(fullTextPage.cursor()).isNull();
            assertThat(hybridPage.cursor()).isNull();
            assertThat(fullTextPage.items()).isNotEmpty().allSatisfy(result -> {
                assertThat(Double.isNaN(result.score())).isTrue();
                assertThat(result.hasScore()).isFalse();
                assertThat(result.optionalScore()).isEmpty();
                assertThat(result.rank()).isPositive();
            });
            assertThat(hybridPage.items()).isNotEmpty().allSatisfy(result -> {
                assertThat(Double.isNaN(result.score())).isTrue();
                assertThat(result.hasScore()).isFalse();
                assertThat(result.optionalScore()).isEmpty();
                assertThat(result.rank()).isPositive();
            });
        }
    }

    private static MemoryPage<MemorySearchResult> searchOrAbort(
            CosmosMemoryStore store, MemoryQuery query, String feature) {
        try {
            return store.searchAsync(query, cancellation())
                    .toCompletableFuture()
                    .join();
        } catch (RuntimeException exception) {
            if (isFeatureUnavailable(exception)) {
                throw new TestAbortedException(
                        "Cosmos emulator doesn't support " + feature + ": " + sanitized(exception));
            }
            throw exception;
        }
    }

    private static boolean isFeatureUnavailable(Throwable failure) {
        boolean supportedStatus = false;
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CosmosException cosmos
                    && (cosmos.getStatusCode() == 400 || cosmos.getStatusCode() == 501)) {
                supportedStatus = true;
            }
            if (current instanceof CosmosStorageException storage
                    && storage.diagnostics() != null
                    && (Integer.valueOf(400).equals(storage.diagnostics().statusCode())
                            || Integer.valueOf(501).equals(storage.diagnostics().statusCode()))) {
                supportedStatus = true;
            }
            if (current.getMessage() != null) {
                messages.append(' ').append(current.getMessage().toLowerCase(Locale.ROOT));
            }
            current = current.getCause();
        }
        String text = messages.toString();
        return supportedStatus
                && (text.contains("not supported")
                        || text.contains("not implemented")
                        || text.contains("unsupported")
                        || text.contains("unknown function")
                        || text.contains("feature is not enabled")
                        || text.contains("enable the feature"));
    }

    private static String sanitized(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static MemoryRecord record(String id, String content, List<Double> vector) {
        return new MemoryRecord(
                new MemoryKey(SCOPE, id),
                content,
                MemoryMetadata.empty(),
                new EmbeddingVector(vector),
                Instant.parse("2026-08-12T00:00:00Z"),
                Instant.parse("2026-08-12T00:00:00Z"),
                null);
    }

    private static DefaultRunCancellation cancellation() {
        return new DefaultRunCancellation();
    }

    private record EmulatorEnvironment(String endpoint, String key) {
        private static EmulatorEnvironment require() {
            String enabled = System.getenv("COSMOS_EMULATOR_TESTS");
            String endpoint = System.getenv("COSMOS_EMULATOR_ENDPOINT");
            String key = System.getenv("COSMOS_EMULATOR_KEY");
            Assumptions.assumeTrue(
                    "true".equalsIgnoreCase(enabled),
                    "Set COSMOS_EMULATOR_TESTS=true to run local Cosmos emulator integration tests.");
            Assumptions.assumeTrue(
                    endpoint != null && !endpoint.isBlank() && key != null && !key.isBlank(),
                    "COSMOS_EMULATOR_ENDPOINT and COSMOS_EMULATOR_KEY are required.");
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            Assumptions.assumeTrue(
                    "https".equalsIgnoreCase(uri.getScheme())
                            && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")),
                    "Cosmos emulator tests only accept loopback HTTPS endpoints.");
            return new EmulatorEnvironment(endpoint, key);
        }

        private CosmosAsyncClient client() {
            return new CosmosClientBuilder()
                    .endpoint(endpoint)
                    .key(key)
                    .consistencyLevel(ConsistencyLevel.SESSION)
                    .gatewayMode()
                    .contentResponseOnWriteEnabled(false)
                    .customItemSerializer(new CosmosMemoryItemSerializer())
                    .buildAsyncClient();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CosmosAsyncClient client;

        private final String databaseId;

        private final CosmosMemoryStore store;

        private Fixture(CosmosAsyncClient client, String databaseId, CosmosMemoryStore store) {
            this.client = client;
            this.databaseId = databaseId;
            this.store = store;
        }

        private static Fixture create(EmulatorEnvironment environment, boolean fullText) {
            CosmosAsyncClient client = environment.client();
            String databaseId = "af-memory-it-" + UUID.randomUUID().toString().replace("-", "");
            String containerId = "memory";
            try {
                client.createDatabaseIfNotExists(databaseId).block();
                CosmosMemoryOptions options = options(databaseId, containerId, fullText);
                CosmosContainerProperties desired = CosmosMemoryProvisioner.desiredContainer(containerId, options);
                try {
                    client.getDatabase(databaseId)
                            .createContainerIfNotExists(desired)
                            .block();
                } catch (RuntimeException exception) {
                    if (isFeatureUnavailable(exception)) {
                        throw new TestAbortedException(
                                "Cosmos emulator rejected required memory policies: " + sanitized(exception));
                    }
                    throw exception;
                }
                CosmosContainerProperties effective = client.getDatabase(databaseId)
                        .getContainer(containerId)
                        .read()
                        .block()
                        .getProperties();
                try {
                    CosmosMemoryProvisioner.validate(effective, options).block();
                } catch (CosmosStorageException exception) {
                    throw new TestAbortedException(
                            "Cosmos emulator didn't retain the required vector/composite/full-text policy.");
                }
                return new Fixture(client, databaseId, new CosmosMemoryStore(client, false, options));
            } catch (RuntimeException exception) {
                try {
                    client.getDatabase(databaseId)
                            .delete()
                            .onErrorResume(ignored -> reactor.core.publisher.Mono.empty())
                            .block();
                } finally {
                    client.close();
                }
                throw exception;
            }
        }

        @Override
        public void close() {
            try {
                client.getDatabase(databaseId)
                        .delete()
                        .onErrorResume(ignored -> reactor.core.publisher.Mono.empty())
                        .block();
            } finally {
                client.close();
            }
        }

        private static CosmosMemoryOptions options(String databaseId, String containerId, boolean fullText) {
            CosmosStorageOptions storage = new CosmosStorageOptions(
                    new CosmosClientOptions(
                            CosmosEndpoint.parse("https://emulator.documents.azure.com/"),
                            CosmosAuthentication.accountKey(CosmosAccountKey.of("local-emulator-key")),
                            new CosmosRetryOptions(2, Duration.ofSeconds(5), Duration.ofSeconds(30)),
                            CosmosConnectionMode.GATEWAY,
                            "agent-framework-emulator-test"),
                    new CosmosContainerOptions(databaseId, containerId, CosmosProvisioningOptions.disabled()),
                    new CosmosPartitionContext("tenant", "principal", "agent"),
                    1_800_000,
                    100,
                    8);
            return new CosmosMemoryOptions(
                    storage,
                    new CosmosMemoryVectorOptions(
                            3, CosmosVectorDataType.FLOAT32, CosmosVectorDistance.COSINE, CosmosVectorIndexType.FLAT),
                    fullText,
                    "en-US",
                    null,
                    25,
                    8,
                    8,
                    CosmosMemoryFallback.DISABLED,
                    100);
        }
    }
}
