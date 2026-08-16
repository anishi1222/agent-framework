// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosBatch;
import com.azure.cosmos.models.CosmosBatchOperationResult;
import com.azure.cosmos.models.CosmosBatchResponse;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosContainerResponse;
import com.azure.cosmos.models.CosmosDatabaseResponse;
import com.azure.cosmos.models.CosmosItemOperation;
import com.azure.cosmos.models.CosmosItemOperationType;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.IndexingPolicy;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.cosmos.util.CosmosPagedFlux;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.AgentSessionStateBag;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.SessionKey;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationRecord;
import com.microsoft.agents.workflows.BufferedInput;
import com.microsoft.agents.workflows.CheckpointCommit;
import com.microsoft.agents.workflows.CheckpointKey;
import com.microsoft.agents.workflows.CheckpointStorage;
import com.microsoft.agents.workflows.InvocationLedgerDelta;
import com.microsoft.agents.workflows.LedgerEntryMutation;
import com.microsoft.agents.workflows.NodeId;
import com.microsoft.agents.workflows.WorkflowCheckpoint;
import com.microsoft.agents.workflows.WorkflowCheckpointCodec;
import com.microsoft.agents.workflows.WorkflowCheckpointStatus;
import com.microsoft.agents.workflows.WorkflowState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CosmosStorageContractTest {
    @Test
    void sdkSerializer_shouldWriteAndParseFrameworkDocumentWithoutNullTtl() {
        // Arrange
        CosmosSessionDocument document = new CosmosSessionDocument();
        document.id = "id";
        document.partitionKey = "partition";
        document.kind = "agent-session";
        document.schemaVersion = 1;
        document.revision = 7L;
        document.deleted = false;
        document.payload = "cGF5bG9hZA==";
        document.payloadDigest = "digest";
        document.ttl = null;

        // Act
        Map<String, Object> serialized = new CosmosNullOmittingItemSerializer().serialize(document);
        CosmosSessionDocument parsed =
                new CosmosNullOmittingItemSerializer().deserialize(serialized, CosmosSessionDocument.class);

        // Assert
        assertThat(serialized)
                .containsEntry("id", "id")
                .containsEntry("partitionKey", "partition")
                .containsEntry("revision", 7L)
                .doesNotContainKey("ttl");
        assertThat(parsed.id).isEqualTo("id");
        assertThat(parsed.partitionKey).isEqualTo("partition");
        assertThat(parsed.revision).isEqualTo(7L);
    }

    @Test
    void sessionStore_shouldUsePointPartitionCreateOnlyAndVersionedCodec() {
        // Arrange
        Fixture fixture = fixture();
        CosmosSessionStore store =
                new CosmosSessionStore(fixture.client, false, sessionOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<CosmosSessionDocument> body = ArgumentCaptor.forClass(CosmosSessionDocument.class);
        ArgumentCaptor<PartitionKey> partition = ArgumentCaptor.forClass(PartitionKey.class);
        ArgumentCaptor<CosmosItemRequestOptions> request = ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
        when(fixture.container.createItem(body.capture(), partition.capture(), request.capture()))
                .thenAnswer(ignored -> Mono.just(itemResponse(null, "etag-1")));
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                "session-1", List.of(Message.text(Role.USER, "hello")), AgentSessionStateBag.empty());

        // Act
        var stored = store.saveAsync(new SessionKey("session-1"), snapshot, CosmosSessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(body.getValue().revision).isEqualTo(1);
        assertThat(body.getValue().payload).isNotBlank();
        assertThat(body.getValue().partitionKey)
                .isEqualTo(partition
                        .getValue()
                        .toString()
                        .replace("[", "")
                        .replace("]", "")
                        .replace("\"", ""));
        assertThat(request.getValue().getIfNoneMatchETag()).isEqualTo("*");
        assertThat(body.getValue().id).doesNotContain("/", "\\", "?", "#");
        assertThat(body.getValue().partitionKey).hasSizeLessThan(100);
    }

    @Test
    void sessionStore_concurrentCas_shouldAllowOneWriterAndRejectPreconditionFailure() {
        // Arrange
        Fixture fixture = fixture();
        CosmosSessionStore store =
                new CosmosSessionStore(fixture.client, false, sessionOptions(fixture.options), serializer());
        AgentSessionSnapshot previous = new AgentSessionSnapshot(
                "session-1", List.of(Message.text(Role.USER, "before")), AgentSessionStateBag.empty());
        CosmosSessionDocument existing = sessionDocument(store, previous, 1);
        CosmosItemResponse<CosmosSessionDocument> currentResponse = itemResponse(existing, "etag-current");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.just(currentResponse));
        CosmosItemResponse<CosmosSessionDocument> nextResponse = itemResponse(existing, "etag-next");
        when(fixture.container.replaceItem(
                        any(CosmosSessionDocument.class),
                        anyString(),
                        any(PartitionKey.class),
                        any(CosmosItemRequestOptions.class)))
                .thenReturn(Mono.just(nextResponse))
                .thenReturn(Mono.error(new TestCosmosException(412)));
        AgentSessionSnapshot first = new AgentSessionSnapshot(
                "session-1", List.of(Message.text(Role.USER, "first")), AgentSessionStateBag.empty());
        AgentSessionSnapshot second = new AgentSessionSnapshot(
                "session-1", List.of(Message.text(Role.USER, "second")), AgentSessionStateBag.empty());

        // Act
        var firstWrite = store.saveAsync(new SessionKey("session-1"), first, 1);
        var secondWrite = store.saveAsync(new SessionKey("session-1"), second, 1);

        // Assert
        assertThat(firstWrite.toCompletableFuture().join().revision()).isEqualTo(2);
        assertThatThrownBy(() -> secondWrite.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
    }

    @Test
    void sessionDeletePolicies_shouldPreserveSoftLineageAndResetHardLineage() {
        // Arrange
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                "session-1", List.of(Message.text(Role.USER, "value")), AgentSessionStateBag.empty());
        SessionKey key = new SessionKey("session-1");

        Fixture softFixture = fixture();
        CosmosSessionStore softStore =
                new CosmosSessionStore(softFixture.client, false, sessionOptions(softFixture.options), serializer());
        CosmosSessionDocument softCurrent = sessionDocument(softStore, snapshot, 1);
        CosmosItemResponse<CosmosSessionDocument> softCurrentResponse = itemResponse(softCurrent, "soft-etag-1");
        when(softFixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.just(softCurrentResponse));
        ArgumentCaptor<CosmosSessionDocument> softWrites = ArgumentCaptor.forClass(CosmosSessionDocument.class);
        CosmosItemResponse<CosmosSessionDocument> softWriteResponse = itemResponse(null, "soft-etag-2");
        when(softFixture.container.replaceItem(
                        softWrites.capture(),
                        anyString(),
                        any(PartitionKey.class),
                        any(CosmosItemRequestOptions.class)))
                .thenReturn(Mono.just(softWriteResponse));

        // Act
        softStore.deleteAsync(key, 1).toCompletableFuture().join();
        CosmosSessionDocument tombstone = softWrites.getValue();
        CosmosItemResponse<CosmosSessionDocument> tombstoneResponse = itemResponse(tombstone, "soft-etag-2");
        when(softFixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.just(tombstoneResponse));
        VersionedSnapshot<AgentSessionSnapshot> recreated = softStore
                .saveAsync(key, snapshot, CosmosSessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(tombstone.deleted).isTrue();
        assertThat(tombstone.revision).isEqualTo(2);
        assertThat(recreated.revision()).isEqualTo(3);

        // Arrange hard-delete lineage
        Fixture hardFixture = fixture();
        CosmosSessionStore hardStore = new CosmosSessionStore(
                hardFixture.client,
                false,
                new CosmosSessionStoreOptions(hardFixture.options, 3600, CosmosDeletePolicy.HARD, 300),
                serializer());
        CosmosSessionDocument hardCurrent = sessionDocument(hardStore, snapshot, 5);
        CosmosItemResponse<CosmosSessionDocument> hardCurrentResponse = itemResponse(hardCurrent, "hard-etag");
        when(hardFixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.just(hardCurrentResponse));
        CosmosItemResponse<Object> hardDeleteResponse = itemResponse(null, "deleted");
        when(hardFixture.container.deleteItem(
                        anyString(), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenReturn(Mono.just(hardDeleteResponse));

        // Act hard-delete and recreate
        hardStore.deleteAsync(key, 5).toCompletableFuture().join();
        when(hardFixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosSessionDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        CosmosItemResponse<CosmosSessionDocument> hardCreateResponse = itemResponse(null, "hard-etag-new");
        when(hardFixture.container.createItem(
                        any(CosmosSessionDocument.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenReturn(Mono.just(hardCreateResponse));
        VersionedSnapshot<AgentSessionSnapshot> reset = hardStore
                .saveAsync(key, snapshot, CosmosSessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(reset.revision()).isEqualTo(1);
    }

    @Test
    void historyAndCheckpointQueries_shouldBePartitionScopedParameterizedAndOrdered() {
        // Arrange
        Fixture fixture = fixture();
        CosmosHistoryProvider history =
                new CosmosHistoryProvider(fixture.client, false, historyOptions(fixture.options), serializer());
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());

        // Act
        SqlQuerySpec historyQuery = history.messageQuery(null);
        SqlQuerySpec checkpointQuery = checkpoints.snapshotQuery();

        // Assert
        assertThat(historyQuery.getQueryText())
                .isEqualTo("SELECT * FROM c WHERE c.kind = @kind ORDER BY c.sequence ASC");
        assertThat(historyQuery.getParameters())
                .extracting(parameter -> parameter.getName())
                .containsExactly("@kind");
        assertThat(history.queryOptions("session-1").getPartitionKey()).isNotNull();
        assertThat(checkpointQuery.getQueryText())
                .isEqualTo("SELECT * FROM c WHERE c.kind = @kind AND c.workflowId = @workflowId"
                        + " ORDER BY c.snapshotSortKey ASC")
                .doesNotContain("IS_DEFINED", "ORDER BY c.revision");
        assertThat(checkpointQuery.getParameters())
                .extracting(parameter -> parameter.getName())
                .containsExactly("@kind", "@workflowId");
        assertThat(checkpoints.queryOptions().getPartitionKey()).isNotNull();
    }

    @Test
    void checkpointSave_shouldWriteExactCanonicalSortKeyOnHeadAndSnapshot() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        CosmosBatchResponse success = mock(CosmosBatchResponse.class);
        when(success.isSuccessStatusCode()).thenReturn(true);
        ArgumentCaptor<CosmosBatch> batch = ArgumentCaptor.forClass(CosmosBatch.class);
        when(fixture.container.executeCosmosBatch(batch.capture())).thenReturn(Mono.just(success));

        // Act
        checkpoints
                .saveAsync(
                        new CheckpointKey("latest"), checkpointDraft("checkpoint-save"), CheckpointStorage.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(batch.getValue().getOperations()).hasSize(2).allSatisfy(operation -> {
            CosmosCheckpointDocument document = operation.getItem();
            assertThat(document.snapshotSortKey).isEqualTo("0000000000000000001:checkpoint-save");
        });
    }

    @Test
    void checkpointLoad_shouldRejectMissingOrMalformedSortKeysReturnedByPointRead() {
        for (String invalidSortKey : new String[] {null, "0000000000000000007:wrong-checkpoint"}) {
            // Arrange
            Fixture fixture = fixture();
            CosmosCheckpointStorage checkpoints = new CosmosCheckpointStorage(
                    fixture.client, false, checkpointOptions(fixture.options), serializer());
            CosmosCheckpointDocument malformed =
                    checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-returned"), 7);
            malformed.snapshotSortKey = invalidSortKey;
            CosmosItemResponse<CosmosCheckpointDocument> response = itemResponse(malformed, "head-etag");
            when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                    .thenReturn(Mono.just(response));

            // Act / Assert
            assertThatThrownBy(() -> checkpoints
                            .loadAsync(new CheckpointKey("latest"))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CosmosStorageException.class);
        }
    }

    @Test
    void checkpointList_shouldUseCanonicalSortKeyForTwoKeysAtSameRevision() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument first =
                checkpointSnapshot(fixture.options, "key-b", checkpointDraft("checkpoint-a"), 1);
        CosmosCheckpointDocument second =
                checkpointSnapshot(fixture.options, "key-a", checkpointDraft("checkpoint-b"), 1);
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointDocument> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointDocument> response = mock(FeedResponse.class);
        when(response.getResults()).thenReturn(List.of(first, second));
        when(response.getContinuationToken()).thenReturn(null);
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointDocument.class)))
                .thenReturn(pages);
        when(pages.byPage(isNull(), eq(25))).thenReturn(Flux.just(response));

        // Act
        CosmosPage<VersionedSnapshot<WorkflowCheckpoint>> page =
                checkpoints.listAsync(null).toCompletableFuture().join();

        // Assert
        assertThat(first.revision).isEqualTo(second.revision);
        assertThat(first.checkpointKey).isNotEqualTo(second.checkpointKey);
        assertThat(first.snapshotSortKey).isEqualTo("0000000000000000001:checkpoint-a");
        assertThat(second.snapshotSortKey).isEqualTo("0000000000000000001:checkpoint-b");
        assertThat(page.items())
                .extracting(snapshot -> snapshot.snapshot().checkpointId())
                .containsExactly("checkpoint-a", "checkpoint-b");
    }

    @Test
    void checkpointList_shouldRejectMissingOrMalformedSortKeysReturnedByQuery() {
        for (String invalidSortKey : new String[] {null, "0000000000000000007:wrong-checkpoint"}) {
            // Arrange
            Fixture fixture = fixture();
            CosmosCheckpointStorage checkpoints = new CosmosCheckpointStorage(
                    fixture.client, false, checkpointOptions(fixture.options), serializer());
            CosmosCheckpointDocument malformed =
                    checkpointSnapshot(fixture.options, "latest", checkpointDraft("checkpoint-returned"), 7);
            malformed.snapshotSortKey = invalidSortKey;
            @SuppressWarnings("unchecked")
            CosmosPagedFlux<CosmosCheckpointDocument> pages = mock(CosmosPagedFlux.class);
            @SuppressWarnings("unchecked")
            FeedResponse<CosmosCheckpointDocument> response = mock(FeedResponse.class);
            when(response.getResults()).thenReturn(List.of(malformed));
            when(response.getContinuationToken()).thenReturn(null);
            when(fixture.container.queryItems(
                            any(SqlQuerySpec.class),
                            any(CosmosQueryRequestOptions.class),
                            eq(CosmosCheckpointDocument.class)))
                    .thenReturn(pages);
            when(pages.byPage(isNull(), eq(25))).thenReturn(Flux.just(response));

            // Act / Assert
            assertThatThrownBy(() ->
                            checkpoints.listAsync(null).toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(CosmosStorageException.class);
        }
    }

    @Test
    void sdkStatusMapping_shouldDistinguishNotFoundConflictAndThrottle() {
        // Act / Assert
        assertThat(CosmosSdkSupport.mapFailure(new TestCosmosException(404)))
                .isInstanceOf(CosmosStorageException.class)
                .extracting(failure -> ((CosmosStorageException) failure).kind())
                .isEqualTo(CosmosStorageException.Kind.NOT_FOUND);
        assertThat(CosmosSdkSupport.hasStatus(new TestCosmosException(412), 412))
                .isTrue();
        assertThat(CosmosSdkSupport.mapFailure(new TestCosmosException(429)))
                .isInstanceOf(CosmosThrottledException.class);
    }

    @Test
    void endpointAndSecret_shouldRejectUnsafeEndpointAndAlwaysRedactKey() {
        // Arrange
        CosmosAccountKey key = CosmosAccountKey.of("super-secret-key");

        // Act / Assert
        assertThat(key.toString()).doesNotContain("super-secret-key").contains("REDACTED");
        assertThatThrownBy(() -> CosmosEndpoint.parse("http://account.documents.azure.com/"))
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
        assertThatThrownBy(() -> CosmosEndpoint.parse("https://account.documents.azure.com/path"))
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
        assertThatThrownBy(() -> CosmosEndpoint.parse("https://evil.example/"))
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
        key.close();
        assertThatThrownBy(key::secretValue).isInstanceOf(com.microsoft.agents.core.ValidationException.class);
    }

    @Test
    void normalizedIdentifiers_shouldBeLengthPrefixedCollisionSafeAndTenantIsolated() {
        // Act
        String splitA = CosmosSdkSupport.itemId("kind", "a", "bc");
        String splitB = CosmosSdkSupport.itemId("kind", "ab", "c");
        String tenantA = CosmosSdkSupport.partitionKey(
                new CosmosPartitionContext("tenant-a", "principal", "agent"), "session", "same");
        String tenantB = CosmosSdkSupport.partitionKey(
                new CosmosPartitionContext("tenant-b", "principal", "agent"), "session", "same");

        // Assert
        assertThat(splitA).isNotEqualTo(splitB);
        assertThat(tenantA).isNotEqualTo(tenantB);
        assertThat(List.of(splitA, splitB, tenantA, tenantB))
                .allSatisfy(identifier -> assertThat(identifier)
                        .startsWith("af1-")
                        .hasSizeLessThan(100)
                        .doesNotContain("tenant", "principal", "same"));
    }

    @Test
    void injectedSdkClient_shouldRemainCallerOwnedAndKeepExactResourceIds() {
        // Arrange
        Fixture injected = fixture();
        CosmosSessionStore injectedStore =
                new CosmosSessionStore(injected.client, false, sessionOptions(injected.options), serializer());

        // Act
        injectedStore.close();

        // Assert
        assertThat(injected.options.container().databaseId()).isEqualTo("db");
        assertThat(injected.options.container().containerId()).isEqualTo("items");
        verify(injected.client, never()).close();
    }

    @Test
    void provisioning_shouldRejectIncompatibleEffectiveContainerWithoutMutation() {
        // Arrange
        CosmosAsyncClient client = mock(CosmosAsyncClient.class);
        CosmosAsyncDatabase database = mock(CosmosAsyncDatabase.class);
        CosmosAsyncContainer container = mock(CosmosAsyncContainer.class);
        CosmosDatabaseResponse databaseResponse = mock(CosmosDatabaseResponse.class);
        CosmosContainerResponse createResponse = mock(CosmosContainerResponse.class);
        CosmosContainerResponse readResponse = mock(CosmosContainerResponse.class);
        CosmosStorageOptions storage = new CosmosStorageOptions(
                new CosmosClientOptions(
                        CosmosEndpoint.parse("https://account.documents.azure.com/"),
                        CosmosAuthentication.accountKey(CosmosAccountKey.of("test-key")),
                        new CosmosRetryOptions(1, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                        CosmosConnectionMode.GATEWAY,
                        "agent-framework-test"),
                new CosmosContainerOptions("db", "items", CosmosProvisioningOptions.itemTimeToLive()),
                new CosmosPartitionContext("tenant", "principal", "agent"),
                1_800_000,
                100,
                8);
        CosmosContainerProperties incompatible = new CosmosContainerProperties("items", "/wrongPartition");
        incompatible.setIndexingPolicy(new IndexingPolicy().setAutomatic(true));
        incompatible.setDefaultTimeToLiveInSeconds(-1);
        when(client.createDatabaseIfNotExists("db")).thenReturn(Mono.just(databaseResponse));
        when(client.getDatabase("db")).thenReturn(database);
        when(database.createContainerIfNotExists(any(CosmosContainerProperties.class)))
                .thenReturn(Mono.just(createResponse));
        when(database.getContainer("items")).thenReturn(container);
        when(container.read()).thenReturn(Mono.just(readResponse));
        when(readResponse.getProperties()).thenReturn(incompatible);

        // Act / Assert
        assertThatThrownBy(() -> CosmosContainerProvisioner.provisionAsync(client, storage)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CosmosStorageException.class)
                .hasRootCauseMessage("Existing Cosmos container must use partition key path /partitionKey.");
        verify(container, never()).replace(any(CosmosContainerProperties.class));
    }

    @Test
    void historyRetry_shouldReuseDeterministicSdkDocumentsWithoutSecondBatch() {
        // Arrange
        Fixture fixture = fixture();
        CosmosHistoryProvider history =
                new CosmosHistoryProvider(fixture.client, false, historyOptions(fixture.options), serializer());
        ContextProviderRequest request = request("session-1", "run-1");
        Message message = Message.builder(Role.USER)
                .contents(List.of(new com.microsoft.agents.core.TextContent("hello")))
                .messageId("message-1")
                .metadata(Map.of("source", StateValue.string("test")))
                .build();
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosHistoryDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosHistoryHeadDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        CosmosBatchResponse success = mock(CosmosBatchResponse.class);
        when(success.isSuccessStatusCode()).thenReturn(true);
        when(fixture.container.executeCosmosBatch(any(CosmosBatch.class))).thenReturn(Mono.just(success));

        // Act
        history.appendMessagesAsync(request, List.of(message))
                .toCompletableFuture()
                .join();
        CosmosHistoryDocument persisted = historyDraft(history, request, message);
        CosmosItemResponse<CosmosHistoryDocument> persistedResponse = itemResponse(persisted, "message-etag");
        when(fixture.container.readItem(eq(persisted.id), any(PartitionKey.class), eq(CosmosHistoryDocument.class)))
                .thenReturn(Mono.just(persistedResponse));
        history.appendMessagesAsync(request, List.of(message))
                .toCompletableFuture()
                .join();

        // Assert
        verify(fixture.container, times(1)).executeCosmosBatch(any(CosmosBatch.class));
        assertThat(persisted.id).doesNotContain(message.text());
        assertThat(new CosmosNullOmittingItemSerializer().serialize(persisted))
                .containsKeys("id", "partitionKey", "payload", "payloadDigest")
                .containsEntry("ttl", 3600);
    }

    @Test
    void checkpointCommit_shouldCreateCheckpointHeadSnapshotAndLedgerInOneSdkBatch() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosLedgerDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        CosmosBatchResponse success = mock(CosmosBatchResponse.class);
        when(success.isSuccessStatusCode()).thenReturn(true);
        ArgumentCaptor<CosmosBatch> batch = ArgumentCaptor.forClass(CosmosBatch.class);
        when(fixture.container.executeCosmosBatch(batch.capture())).thenReturn(Mono.just(success));
        WorkflowCheckpoint draft = checkpointDraft("checkpoint-1");
        InvocationRecord pending =
                new InvocationRecord(new InvocationId("invocation-1"), "run-1", "call-1", "tool-1", "digest-1");
        CheckpointCommit commit = new CheckpointCommit(
                new CheckpointKey("latest"),
                draft,
                new InvocationLedgerDelta(List.of(new LedgerEntryMutation(pending, 0))));

        // Act
        VersionedSnapshot<WorkflowCheckpoint> stored = checkpoints
                .commitAsync(commit, CheckpointStorage.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(stored.snapshot().checkpointId()).isEqualTo("checkpoint-1");
        assertThat(batch.getValue().getOperations()).hasSize(3);
        assertThat(batch.getValue().getPartitionKeyValue()).isNotNull();
        assertThat(batch.getValue().getOperations().subList(0, 2)).allSatisfy(operation -> {
            CosmosCheckpointDocument document = operation.getItem();
            assertThat(document.snapshotSortKey).isEqualTo("0000000000000000001:checkpoint-1");
        });
    }

    @Test
    void checkpointConcurrentCommit_shouldMapBatchPreconditionFailureToStorageConflict() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument head = checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-1"), 1);
        CosmosItemResponse<CosmosCheckpointDocument> headResponse = itemResponse(head, "head-etag");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.just(headResponse));
        CosmosBatchResponse preconditionFailed = mock(CosmosBatchResponse.class);
        when(preconditionFailed.isSuccessStatusCode()).thenReturn(false);
        when(preconditionFailed.getStatusCode()).thenReturn(412);
        when(fixture.container.executeCosmosBatch(any(CosmosBatch.class))).thenReturn(Mono.just(preconditionFailed));

        // Act / Assert
        assertThatThrownBy(() -> checkpoints
                        .saveAsync(new CheckpointKey("latest"), checkpointDraft("checkpoint-2"), 1)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
    }

    @Test
    void checkpointPurge_shouldFollowEmptyContinuationPagesAndDeleteOnlyTargetSnapshotsThenHead() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument head = checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-2"), 2);
        CosmosItemResponse<CosmosCheckpointDocument> headResponse = itemResponse(head, "head-etag");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.just(headResponse));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> emptyIntermediate = mock(FeedResponse.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> documents = mock(FeedResponse.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> terminalEmpty = mock(FeedResponse.class);
        when(emptyIntermediate.getResults()).thenReturn(List.of());
        when(emptyIntermediate.getContinuationToken()).thenReturn("continue");
        when(documents.getResults())
                .thenReturn(List.of(
                        purgeRow("snapshot-1", "workflow-checkpoint", "latest", "snapshot-etag-1"),
                        purgeRow("snapshot-2", "workflow-checkpoint", "latest", "snapshot-etag-2")));
        when(terminalEmpty.getResults()).thenReturn(List.of());
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99))
                .thenReturn(Flux.just(emptyIntermediate, documents))
                .thenReturn(Flux.just(terminalEmpty));
        CosmosBatchResponse success = mock(CosmosBatchResponse.class);
        CosmosBatchOperationResult replacement = mock(CosmosBatchOperationResult.class);
        when(success.isSuccessStatusCode()).thenReturn(true);
        when(success.getResults()).thenReturn(List.of(replacement));
        when(replacement.getETag()).thenReturn("head-etag-2");
        ArgumentCaptor<CosmosBatch> batches = ArgumentCaptor.forClass(CosmosBatch.class);
        when(fixture.container.executeCosmosBatch(batches.capture()))
                .thenReturn(Mono.just(success))
                .thenReturn(Mono.just(success));

        // Act
        CosmosCheckpointPurgeResult result = checkpoints
                .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.COMPLETED);
        assertThat(result.isComplete()).isTrue();
        assertThat(result.deletedHeads()).isEqualTo(1);
        assertThat(result.deletedSnapshots()).isEqualTo(2);
        assertThat(result.completedBatches()).isEqualTo(2);
        assertThat(batches.getAllValues())
                .extracting(batch -> batch.getOperations().size())
                .containsExactly(3, 1);
        assertThat(batches.getAllValues().getFirst().getOperations())
                .extracting(CosmosItemOperation::getOperationType, CosmosItemOperation::getId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CosmosItemOperationType.REPLACE, head.id),
                        org.assertj.core.groups.Tuple.tuple(CosmosItemOperationType.DELETE, "snapshot-1"),
                        org.assertj.core.groups.Tuple.tuple(CosmosItemOperationType.DELETE, "snapshot-2"));
        assertThat(batches.getAllValues().getFirst().getOperations())
                .extracting(CosmosStorageContractTest::ifMatchEtag)
                .containsExactly("head-etag", "snapshot-etag-1", "snapshot-etag-2");
        CosmosCheckpointDocument fencedHead =
                batches.getAllValues().getFirst().getOperations().getFirst().getItem();
        assertThat(fencedHead.ttl).isBetween(1, 3600);
        assertThat(fencedHead._ts).isNull();
        assertThat(batches.getAllValues().get(1).getOperations())
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.getOperationType()).isEqualTo(CosmosItemOperationType.DELETE);
                    assertThat(operation.getId()).isEqualTo(head.id);
                    assertThat(ifMatchEtag(operation)).isEqualTo("head-etag-2");
                });
        assertThat(checkpoints.purgeQuery("latest").getQueryText())
                .isEqualTo("SELECT c.id, c.kind, c.checkpointKey, c._etag AS etag FROM c"
                        + " WHERE c.kind = @kind AND c.checkpointKey = @checkpointKey")
                .contains("c._etag AS etag")
                .doesNotContain(" OR ", "invocation-ledger", "checkpoint-head");
        assertThat(checkpoints.purgeQuery("latest").getParameters())
                .extracting(SqlParameter::getName)
                .containsExactly("@kind", "@checkpointKey");
    }

    @Test
    void checkpointPurge_shouldReturnPartialConflictReportAndKeepHeadForRetry() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument head = checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-2"), 2);
        CosmosItemResponse<CosmosCheckpointDocument> headResponse = itemResponse(head, "head-etag");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.just(headResponse));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> first = mock(FeedResponse.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> second = mock(FeedResponse.class);
        when(first.getResults())
                .thenReturn(List.of(purgeRow("snapshot-1", "workflow-checkpoint", "latest", "snapshot-etag-1")));
        when(second.getResults())
                .thenReturn(List.of(purgeRow("snapshot-2", "workflow-checkpoint", "latest", "snapshot-etag-2")));
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(first)).thenReturn(Flux.just(second));
        CosmosBatchResponse success = mock(CosmosBatchResponse.class);
        CosmosBatchOperationResult replacement = mock(CosmosBatchOperationResult.class);
        when(success.isSuccessStatusCode()).thenReturn(true);
        when(success.getResults()).thenReturn(List.of(replacement));
        when(replacement.getETag()).thenReturn("head-etag-2");
        CosmosBatchResponse conflict = mock(CosmosBatchResponse.class);
        when(conflict.isSuccessStatusCode()).thenReturn(false);
        when(conflict.getStatusCode()).thenReturn(424);
        CosmosBatchOperationResult precondition = mock(CosmosBatchOperationResult.class);
        when(precondition.isSuccessStatusCode()).thenReturn(false);
        when(precondition.getStatusCode()).thenReturn(412);
        when(conflict.getResults()).thenReturn(List.of(precondition));
        ArgumentCaptor<CosmosBatch> batches = ArgumentCaptor.forClass(CosmosBatch.class);
        when(fixture.container.executeCosmosBatch(batches.capture()))
                .thenReturn(Mono.just(success))
                .thenReturn(Mono.just(conflict));

        // Act
        CosmosCheckpointPurgeResult result = checkpoints
                .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.CONFLICT);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.deletedHeads()).isZero();
        assertThat(result.deletedSnapshots()).isEqualTo(1);
        assertThat(result.completedBatches()).isEqualTo(1);
        assertThat(result.serviceStatusCode()).isEqualTo(412);
        assertThat(batches.getAllValues()).hasSize(2);
        CosmosItemOperation firstFence =
                batches.getAllValues().getFirst().getOperations().getFirst();
        CosmosItemOperation secondFence =
                batches.getAllValues().get(1).getOperations().getFirst();
        assertThat(firstFence.getOperationType()).isEqualTo(CosmosItemOperationType.REPLACE);
        assertThat(firstFence.getId()).isEqualTo(head.id);
        assertThat(ifMatchEtag(firstFence)).isEqualTo("head-etag");
        assertThat(secondFence.getOperationType()).isEqualTo(CosmosItemOperationType.REPLACE);
        assertThat(secondFence.getId()).isEqualTo(head.id);
        assertThat(ifMatchEtag(secondFence)).isEqualTo("head-etag-2");
    }

    @Test
    void checkpointPurge_shouldBeIdempotentWhenHeadIsAlreadyAbsent() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> empty = mock(FeedResponse.class);
        when(empty.getResults()).thenReturn(List.of());
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(empty));

        // Act
        CosmosCheckpointPurgeResult result = checkpoints
                .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.ALREADY_PURGED);
        assertThat(result.isComplete()).isTrue();
        assertThat(result.completedBatches()).isZero();
        verify(fixture.container, never()).executeCosmosBatch(any(CosmosBatch.class));
    }

    @Test
    void checkpointDelete_shouldConflictWhenTargetKeyIsAlreadyAbsent() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> empty = mock(FeedResponse.class);
        when(empty.getResults()).thenReturn(List.of());
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(empty));

        // Act / Assert
        assertThatThrownBy(() -> checkpoints
                        .deleteAsync(new CheckpointKey("latest"), 2)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
        verify(fixture.container, never()).executeCosmosBatch(any(CosmosBatch.class));
    }

    @Test
    void checkpointPurge_shouldReportConflictWhenHeadIsAbsentButOrphanSnapshotsRemain() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.error(new TestCosmosException(404)));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> emptyIntermediate = mock(FeedResponse.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> orphan = mock(FeedResponse.class);
        when(emptyIntermediate.getResults()).thenReturn(List.of());
        when(emptyIntermediate.getContinuationToken()).thenReturn("continue");
        when(orphan.getResults())
                .thenReturn(List.of(purgeRow("orphan-snapshot", "workflow-checkpoint", "latest", "orphan-etag")));
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(emptyIntermediate, orphan));

        // Act
        CosmosCheckpointPurgeResult result = checkpoints
                .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.CONFLICT);
        assertThat(result.isComplete()).isFalse();
        assertThat(result.deletedHeads()).isZero();
        assertThat(result.deletedSnapshots()).isZero();
        assertThat(result.completedBatches()).isZero();
        verify(fixture.container, never()).executeCosmosBatch(any(CosmosBatch.class));
    }

    @Test
    void checkpointPurge_shouldRejectUnexpectedCrossKeyProjectionWithoutEffects() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument head = checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-2"), 2);
        CosmosItemResponse<CosmosCheckpointDocument> headResponse = itemResponse(head, "head-etag");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.just(headResponse));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> crossKey = mock(FeedResponse.class);
        when(crossKey.getResults())
                .thenReturn(List.of(purgeRow("other-snapshot", "workflow-checkpoint", "other-key", "other-etag")));
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(crossKey));

        // Act
        CosmosCheckpointPurgeResult result = checkpoints
                .purgeAsync(new CheckpointKey("latest"), 2, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.status()).isEqualTo(CosmosCheckpointPurgeResult.Status.FAILED);
        assertThat(result.isComplete()).isFalse();
        verify(fixture.container, never()).executeCosmosBatch(any(CosmosBatch.class));
    }

    @Test
    void checkpointDelete_shouldFailWhenKeyScopedPurgeReturnsPartialReport() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        CosmosCheckpointDocument head = checkpointHead(fixture.options, "latest", checkpointDraft("checkpoint-2"), 2);
        CosmosItemResponse<CosmosCheckpointDocument> headResponse = itemResponse(head, "head-etag");
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.just(headResponse));
        @SuppressWarnings("unchecked")
        CosmosPagedFlux<CosmosCheckpointPurgeRow> pages = mock(CosmosPagedFlux.class);
        @SuppressWarnings("unchecked")
        FeedResponse<CosmosCheckpointPurgeRow> snapshot = mock(FeedResponse.class);
        when(snapshot.getResults())
                .thenReturn(List.of(purgeRow("snapshot-1", "workflow-checkpoint", "latest", "snapshot-etag")));
        when(fixture.container.queryItems(
                        any(SqlQuerySpec.class),
                        any(CosmosQueryRequestOptions.class),
                        eq(CosmosCheckpointPurgeRow.class)))
                .thenReturn(pages);
        when(pages.byPage(99)).thenReturn(Flux.just(snapshot));
        CosmosBatchResponse conflict = mock(CosmosBatchResponse.class);
        when(conflict.isSuccessStatusCode()).thenReturn(false);
        when(conflict.getStatusCode()).thenReturn(412);
        when(fixture.container.executeCosmosBatch(any(CosmosBatch.class))).thenReturn(Mono.just(conflict));

        // Act / Assert
        assertThatThrownBy(() -> checkpoints
                        .deleteAsync(new CheckpointKey("latest"), 2)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StorageConflictException.class);
    }

    @Test
    void checkpointPurge_shouldHonorCallerCancellationBeforeEffects() {
        // Arrange
        Fixture fixture = fixture();
        CosmosCheckpointStorage checkpoints =
                new CosmosCheckpointStorage(fixture.client, false, checkpointOptions(fixture.options), serializer());
        when(fixture.container.readItem(anyString(), any(PartitionKey.class), eq(CosmosCheckpointDocument.class)))
                .thenReturn(Mono.never());
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        cancellation.cancel();

        // Act / Assert
        assertThatThrownBy(() -> checkpoints
                        .purgeAsync(new CheckpointKey("latest"), 2, cancellation)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
        verify(fixture.container, never()).executeCosmosBatch(any(CosmosBatch.class));
    }

    @Test
    void checkpointCodec_shouldRoundTripPreviousFingerprintAndFanInStateThroughSdkDocument() {
        // Arrange
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(
                "workflow-1",
                "checkpoint-2",
                9,
                "checkpoint-1",
                WorkflowCheckpointStatus.RUNNING,
                List.of(new NodeId("target")),
                List.of(new BufferedInput(new NodeId("target"), "source", StateValue.string("value"))),
                Map.of(new NodeId("target"), 3L),
                2,
                "sha256:graph",
                "run-1",
                4,
                WorkflowState.empty());
        WorkflowCheckpointCodec codec = new WorkflowCheckpointCodec(serializer());
        CosmosCheckpointDocument document = new CosmosCheckpointDocument();
        document.id = "id";
        document.partitionKey = "partition";
        document.kind = "workflow-checkpoint";
        document.schemaVersion = 1;
        document.checkpointKey = "latest";
        document.workflowId = "workflow-1";
        document.checkpointId = "checkpoint-2";
        document.revision = 9L;
        byte[] payload = codec.encode(checkpoint);
        document.payload = java.util.Base64.getEncoder().encodeToString(payload);
        document.payloadDigest = CosmosSdkSupport.payloadDigest(payload);

        // Act
        Map<String, Object> sdkBody = new CosmosNullOmittingItemSerializer().serialize(document);
        CosmosCheckpointDocument parsed =
                new CosmosNullOmittingItemSerializer().deserialize(sdkBody, CosmosCheckpointDocument.class);
        WorkflowCheckpoint restored = codec.decode(java.util.Base64.getDecoder().decode(parsed.payload));

        // Assert
        assertThat(restored.previousCheckpointId()).isEqualTo("checkpoint-1");
        assertThat(restored.graphFingerprint()).isEqualTo("sha256:graph");
        assertThat(restored.fanInNextEpochs()).containsEntry(new NodeId("target"), 3L);
        assertThat(restored.bufferedInputs()).hasSize(1);
    }

    private static CosmosSessionDocument sessionDocument(
            CosmosSessionStore store, AgentSessionSnapshot snapshot, long revision) {
        try {
            var method = CosmosSessionStore.class.getDeclaredMethod(
                    "document", SessionKey.class, byte[].class, String.class, long.class, boolean.class);
            method.setAccessible(true);
            byte[] payload = new com.microsoft.agents.agents.AgentSessionCodec(serializer()).encode(snapshot);
            return (CosmosSessionDocument) method.invoke(
                    store,
                    new SessionKey(snapshot.sessionId()),
                    payload,
                    CosmosSdkSupport.payloadDigest(payload),
                    revision,
                    false);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static CosmosHistoryDocument historyDraft(
            CosmosHistoryProvider history, ContextProviderRequest request, Message message) {
        try {
            var method =
                    CosmosHistoryProvider.class.getDeclaredMethod("drafts", ContextProviderRequest.class, List.class);
            method.setAccessible(true);
            List<CosmosHistoryDocument> documents =
                    (List<CosmosHistoryDocument>) method.invoke(history, request, List.of(message));
            CosmosHistoryDocument persisted = documents.getFirst();
            persisted.sequence = 0L;
            return persisted;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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

    private static WorkflowCheckpoint checkpointDraft(String checkpointId) {
        return new WorkflowCheckpoint(
                "workflow-1",
                checkpointId,
                0,
                null,
                WorkflowCheckpointStatus.RUNNING,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                WorkflowState.empty());
    }

    private static CosmosCheckpointDocument checkpointHead(
            CosmosStorageOptions storage, String key, WorkflowCheckpoint draft, long revision) {
        CosmosCheckpointDocument document = checkpointDocument(storage, key, draft, revision);
        document.id = CosmosSdkSupport.itemId("checkpoint-head", "workflow-1", key);
        document.kind = "checkpoint-head";
        return document;
    }

    private static CosmosCheckpointDocument checkpointSnapshot(
            CosmosStorageOptions storage, String key, WorkflowCheckpoint draft, long revision) {
        CosmosCheckpointDocument document = checkpointDocument(storage, key, draft, revision);
        document.id = CosmosSdkSupport.itemId("workflow-checkpoint", "workflow-1", draft.checkpointId());
        document.kind = "workflow-checkpoint";
        document.snapshotSortKey = CosmosCheckpointStorage.snapshotSortKey(revision, draft.checkpointId());
        return document;
    }

    private static CosmosCheckpointDocument checkpointDocument(
            CosmosStorageOptions storage, String key, WorkflowCheckpoint draft, long revision) {
        WorkflowCheckpoint stored = draft.withRevision(revision);
        byte[] payload = new WorkflowCheckpointCodec(serializer()).encode(stored);
        CosmosCheckpointDocument document = new CosmosCheckpointDocument();
        document.partitionKey = CosmosSdkSupport.partitionKey(storage.partition(), "workflow", "workflow-1");
        document.schemaVersion = 1;
        document.checkpointKey = key;
        document.workflowId = "workflow-1";
        document.checkpointId = stored.checkpointId();
        document.revision = revision;
        document.snapshotSortKey = CosmosCheckpointStorage.snapshotSortKey(revision, stored.checkpointId());
        document.payload = java.util.Base64.getEncoder().encodeToString(payload);
        document.payloadDigest = CosmosSdkSupport.payloadDigest(payload);
        document.ttl = 3600;
        document._ts = Instant.now().getEpochSecond();
        return document;
    }

    private static CosmosCheckpointPurgeRow purgeRow(String id, String kind, String checkpointKey, String etag) {
        CosmosCheckpointPurgeRow row = new CosmosCheckpointPurgeRow();
        row.id = id;
        row.kind = kind;
        row.checkpointKey = checkpointKey;
        row.etag = etag;
        return row;
    }

    private static JsonStateSerializer serializer() {
        return new JsonStateSerializer(new SerializationLimits(1_800_000, 64, 250_000, 128, 50_000));
    }

    private static CosmosSessionStoreOptions sessionOptions(CosmosStorageOptions storage) {
        return new CosmosSessionStoreOptions(storage, 3600, CosmosDeletePolicy.SOFT, 300);
    }

    private static CosmosHistoryOptions historyOptions(CosmosStorageOptions storage) {
        return new CosmosHistoryOptions(storage, "cosmos-history", 3600, 100, 25, 99, 4);
    }

    private static CosmosCheckpointOptions checkpointOptions(CosmosStorageOptions storage) {
        return new CosmosCheckpointOptions(storage, "workflow-1", 3600, 25);
    }

    private static Fixture fixture() {
        CosmosAsyncClient client = mock(CosmosAsyncClient.class);
        CosmosAsyncDatabase database = mock(CosmosAsyncDatabase.class);
        CosmosAsyncContainer container = mock(CosmosAsyncContainer.class);
        when(client.getDatabase("db")).thenReturn(database);
        when(database.getContainer("items")).thenReturn(container);
        CosmosClientOptions clientOptions = new CosmosClientOptions(
                CosmosEndpoint.parse("https://account.documents.azure.com/"),
                CosmosAuthentication.accountKey(CosmosAccountKey.of("test-key")),
                new CosmosRetryOptions(1, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                CosmosConnectionMode.GATEWAY,
                "agent-framework-test");
        CosmosStorageOptions options = new CosmosStorageOptions(
                clientOptions,
                new CosmosContainerOptions("db", "items", CosmosProvisioningOptions.disabled()),
                new CosmosPartitionContext("tenant", "principal", "agent"),
                1_800_000,
                100,
                8);
        return new Fixture(client, container, options);
    }

    @SuppressWarnings("unchecked")
    private static <T> CosmosItemResponse<T> itemResponse(T item, String etag) {
        CosmosItemResponse<T> response = (CosmosItemResponse<T>) mock(CosmosItemResponse.class);
        when(response.getItem()).thenReturn(item);
        when(response.getETag()).thenReturn(etag);
        return response;
    }

    private static String ifMatchEtag(CosmosItemOperation operation) {
        try {
            Object requestOptions =
                    operation.getClass().getMethod("getRequestOptions").invoke(operation);
            return (String)
                    requestOptions.getClass().getMethod("getIfMatchETag").invoke(requestOptions);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(CosmosAsyncClient client, CosmosAsyncContainer container, CosmosStorageOptions options) {}

    private static final class TestCosmosException extends CosmosException {
        private static final long serialVersionUID = 1L;

        private TestCosmosException(int statusCode) {
            super(statusCode, "test");
        }
    }
}
