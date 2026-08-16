// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.IndexingPolicy;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationRecord;
import com.microsoft.agents.workflows.CheckpointCommit;
import com.microsoft.agents.workflows.CheckpointKey;
import com.microsoft.agents.workflows.CheckpointStorage;
import com.microsoft.agents.workflows.InvocationLedgerDelta;
import com.microsoft.agents.workflows.LedgerEntryMutation;
import com.microsoft.agents.workflows.WorkflowCheckpoint;
import com.microsoft.agents.workflows.WorkflowCheckpointStatus;
import com.microsoft.agents.workflows.WorkflowState;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in local-emulator coverage.
 *
 * <p>Set {@code COSMOS_EMULATOR_TESTS=true}, {@code COSMOS_EMULATOR_ENDPOINT} to a loopback HTTPS
 * endpoint, and {@code COSMOS_EMULATOR_KEY} to the local emulator key. The Java trust store must
 * trust the emulator certificate. Non-loopback endpoints are always skipped, so this test never
 * uses live Azure credentials.
 */
class CosmosEmulatorIntegrationTest {
    @Test
    void emulator_shouldExecuteHistoryAndKeyScopedCheckpointPurge() {
        EmulatorEnvironment environment = EmulatorEnvironment.require();
        String databaseId = "af-it-" + UUID.randomUUID().toString().replace("-", "");
        String containerId = "state";
        CosmosAsyncClient client = environment.client(new CosmosNullOmittingItemSerializer());
        try {
            client.createDatabaseIfNotExists(databaseId).block();
            CosmosContainerProperties properties =
                    new CosmosContainerProperties(containerId, CosmosContainerOptions.PARTITION_KEY_PATH);
            properties.setIndexingPolicy(new IndexingPolicy().setAutomatic(true));
            properties.setDefaultTimeToLiveInSeconds(-1);
            client.getDatabase(databaseId)
                    .createContainerIfNotExists(properties)
                    .block();

            CosmosStorageOptions storage = storage(databaseId, containerId);
            JsonStateSerializer serializer = serializer();
            CosmosHistoryProvider history = new CosmosHistoryProvider(
                    client,
                    false,
                    new CosmosHistoryOptions(storage, "emulator-history", 3600, 100, 25, 99, 4),
                    serializer);
            ContextProviderRequest request = request("session-1", "run-1");
            Message first = Message.builder(Role.USER)
                    .messageId("message-1")
                    .contents(List.of(new com.microsoft.agents.core.TextContent("first")))
                    .build();
            Message second = Message.builder(Role.ASSISTANT)
                    .messageId("message-2")
                    .contents(List.of(new com.microsoft.agents.core.TextContent("second")))
                    .build();
            history.appendMessagesAsync(request, List.of(first, second))
                    .toCompletableFuture()
                    .join();

            assertThat(history.loadMessagesAsync(request).toCompletableFuture().join())
                    .extracting(Message::text)
                    .containsExactly("first", "second");

            CosmosCheckpointStorage checkpoints = new CosmosCheckpointStorage(
                    client, false, new CosmosCheckpointOptions(storage, "workflow-1", 3600, 25), serializer);
            InvocationRecord ledger =
                    new InvocationRecord(new InvocationId("invocation-1"), "run-1", "call-1", "tool-1", "digest-1");
            checkpoints
                    .commitAsync(
                            new CheckpointCommit(
                                    new CheckpointKey("latest"),
                                    checkpoint("checkpoint-1", null),
                                    new InvocationLedgerDelta(List.of(new LedgerEntryMutation(ledger, 0)))),
                            -1)
                    .toCompletableFuture()
                    .join();
            checkpoints
                    .saveAsync(new CheckpointKey("latest"), checkpoint("checkpoint-2", "checkpoint-1"), 1)
                    .toCompletableFuture()
                    .join();
            checkpoints
                    .saveAsync(
                            new CheckpointKey("retained"),
                            checkpoint("checkpoint-other", null),
                            CheckpointStorage.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();

            assertThat(checkpoints.listAsync(null).toCompletableFuture().join().items())
                    .extracting(snapshot -> snapshot.snapshot().checkpointId())
                    .containsExactly("checkpoint-1", "checkpoint-other", "checkpoint-2");

            CosmosCheckpointPurgeResult purge = checkpoints
                    .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(purge.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.COMPLETED);
            assertThat(purge.deletedHeads()).isEqualTo(1);
            assertThat(purge.deletedSnapshots()).isEqualTo(2);
            assertThat(checkpoints.listAsync(null).toCompletableFuture().join().items())
                    .extracting(snapshot -> snapshot.snapshot().checkpointId())
                    .containsExactly("checkpoint-other");
            assertThat(checkpoints
                            .loadAsync(new CheckpointKey("latest"))
                            .toCompletableFuture()
                            .join())
                    .isEmpty();
            assertThat(checkpoints
                            .loadAsync(new CheckpointKey("retained"))
                            .toCompletableFuture()
                            .join())
                    .isPresent();
            assertThat(checkpoints
                            .loadLedgerAsync(new InvocationId("invocation-1"))
                            .toCompletableFuture()
                            .join())
                    .isPresent();
            assertThat(checkpoints
                            .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join()
                            .status())
                    .isEqualTo(CosmosCheckpointPurgeResult.Status.ALREADY_PURGED);
        } finally {
            try {
                client.getDatabase(databaseId)
                        .delete()
                        .onErrorResume(ignored -> reactor.core.publisher.Mono.empty())
                        .block();
            } finally {
                client.close();
            }
        }
    }

    private static CosmosStorageOptions storage(String databaseId, String containerId) {
        return new CosmosStorageOptions(
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
    }

    private static ContextProviderRequest request(String sessionId, String runId) {
        AgentSession session = new AgentSession(sessionId);
        Message input = Message.text(Role.USER, "input");
        AgentRunContext runContext = new AgentRunContext(
                runId,
                new AgentMetadata("agent", null, null),
                Instant.now(),
                List.of(input),
                RunOptions.empty(),
                new DefaultRunCancellation(),
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(input), List.of(), Map.of(), List.of());
    }

    private static WorkflowCheckpoint checkpoint(String checkpointId, String previousCheckpointId) {
        return new WorkflowCheckpoint(
                "workflow-1",
                checkpointId,
                0,
                previousCheckpointId,
                WorkflowCheckpointStatus.RUNNING,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                WorkflowState.empty());
    }

    private static JsonStateSerializer serializer() {
        return new JsonStateSerializer(new SerializationLimits(1_800_000, 64, 250_000, 128, 50_000));
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

        private CosmosAsyncClient client(com.azure.cosmos.CosmosItemSerializer serializer) {
            return new CosmosClientBuilder()
                    .endpoint(endpoint)
                    .key(key)
                    .consistencyLevel(ConsistencyLevel.SESSION)
                    .gatewayMode()
                    .contentResponseOnWriteEnabled(false)
                    .customItemSerializer(serializer)
                    .buildAsyncClient();
        }
    }
}
