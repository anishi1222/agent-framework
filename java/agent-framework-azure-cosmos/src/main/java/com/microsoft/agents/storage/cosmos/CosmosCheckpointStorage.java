// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CosmosBatch;
import com.azure.cosmos.models.CosmosBatchItemRequestOptions;
import com.azure.cosmos.models.CosmosBatchResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationLedgerEntry;
import com.microsoft.agents.workflows.CheckpointCommit;
import com.microsoft.agents.workflows.CheckpointKey;
import com.microsoft.agents.workflows.CheckpointStorage;
import com.microsoft.agents.workflows.CheckpointStorageDurability;
import com.microsoft.agents.workflows.InvocationLedgerDelta;
import com.microsoft.agents.workflows.LedgerEntryMutation;
import com.microsoft.agents.workflows.StorageCapability;
import com.microsoft.agents.workflows.WorkflowCheckpoint;
import com.microsoft.agents.workflows.WorkflowCheckpointCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/**
 * Stores workflow checkpoints and invocation-ledger mutations atomically in one workflow partition.
 *
 * <p>The store is bound to one exact workflow ID. Every saved checkpoint is an immutable document
 * keyed by checkpoint ID; a separate ETag-protected head keyed by {@link CheckpointKey} implements
 * the framework CAS SPI. This preserves historical checkpoints for bounded listing while load and
 * key-scoped deletion remain isolated from other checkpoint keys and the workflow-wide invocation
 * ledger.
 */
public final class CosmosCheckpointStorage implements CheckpointStorage, AutoCloseable {
    private static final String HEAD_KIND = "checkpoint-head";

    private static final String SNAPSHOT_KIND = "workflow-checkpoint";

    private static final int SCHEMA_VERSION = 1;

    private static final int MAX_PURGE_DELETES_PER_BATCH = 99;

    private static final Set<StorageCapability> CAPABILITIES = Set.of(StorageCapability.ATOMIC_CHECKPOINT_AND_LEDGER);

    private final CosmosCheckpointOptions options;

    private final WorkflowCheckpointCodec codec;

    private final CosmosLedgerCodec ledgerCodec = new CosmosLedgerCodec();

    private final CosmosAsyncClient client;

    private final CosmosAsyncContainer container;

    private final boolean ownsClient;

    private final CompletionStage<Void> initialization;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<CompletionStage<Integer>> defaultTtlPreparation = new AtomicReference<>();

    /**
     * Creates storage that owns one resilient SDK client.
     *
     * @param options workflow-bound checkpoint options
     * @param serializer safe versioned state serializer
     * @return Cosmos checkpoint storage
     */
    public static CosmosCheckpointStorage create(CosmosCheckpointOptions options, JsonStateSerializer serializer) {
        CosmosCheckpointOptions checked = CosmosValidation.requireNonNull(options, "options");
        CosmosAsyncClient client = CosmosClientFactory.create(checked.storage().client());
        return new CosmosCheckpointStorage(client, true, checked, serializer);
    }

    CosmosCheckpointStorage(
            CosmosAsyncClient client,
            boolean ownsClient,
            CosmosCheckpointOptions options,
            JsonStateSerializer serializer) {
        this.client = CosmosValidation.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.options = CosmosValidation.requireNonNull(options, "options");
        this.codec = new WorkflowCheckpointCodec(CosmosValidation.requireNonNull(serializer, "serializer"));
        this.container =
                CosmosContainerProvisioner.container(client, options.storage().container());
        this.initialization = CosmosContainerProvisioner.provisionAsync(client, options.storage());
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<Optional<VersionedSnapshot<WorkflowCheckpoint>>> loadAsync(CheckpointKey key) {
        ValidationException validation = validateKey(key);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> readHead(key).thenApply(read -> {
            if (read == null) {
                return Optional.empty();
            }
            WorkflowCheckpoint checkpoint = decode(read.document);
            return Optional.of(new VersionedSnapshot<>(checkpoint, read.document.revision));
        }));
    }

    @Override
    public CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> saveAsync(
            CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision) {
        ValidationException validation = validateSave(key, checkpoint, expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> readHead(key)
                .thenCompose(head -> prepareWrite(key, checkpoint, expectedRevision, head))
                .thenCompose(write -> executeCheckpointWrite(write, List.of()))
                .thenApply(write -> write.versioned));
    }

    /**
     * Deletes one checkpoint key and its immutable snapshot history.
     *
     * <p>This SPI method delegates to {@link #purgeAsync(CheckpointKey,long,RunCancellation)} with an
     * independent cancellation token and fails if the purge returns a partial report. Other
     * checkpoint keys and workflow-wide invocation-ledger entries are never deleted.
     *
     * @param key checkpoint key whose head fences the purge
     * @param expectedRevision positive expected head revision
     * @return completion stage
     */
    @Override
    public CompletionStage<Void> deleteAsync(CheckpointKey key, long expectedRevision) {
        return purgeAsync(key, expectedRevision, new DefaultRunCancellation()).thenCompose(result -> {
            if (result.status() == CosmosCheckpointPurgeResult.Status.COMPLETED) {
                return CompletableFuture.completedStage(null);
            }
            return CompletableFuture.failedStage(purgeFailure(key, expectedRevision, result));
        });
    }

    /**
     * Removes the target checkpoint head and every immutable snapshot for the same checkpoint key.
     *
     * <p>Each query page and transactional delete batch is bounded. A conditional replacement of
     * the unchanged target head fences every snapshot batch and carries its new ETag forward; each
     * projected item is deleted with its own ETag, and the target head is deleted last. A concurrent
     * write therefore produces an incomplete report rather than deleting a newer value.
     * Cancellation completes exceptionally with {@link RunCancelledException}. An absent target
     * head is treated as already purged only after a complete key-scoped query confirms that no
     * orphan snapshots remain.
     *
     * <p>Other checkpoint heads, snapshots for other keys, and workflow-wide invocation-ledger
     * documents remain untouched.
     *
     * @param key checkpoint key whose head fences the purge
     * @param expectedRevision positive expected head revision
     * @param cancellation caller-owned cancellation
     * @return complete or partial sanitized purge report
     */
    public CompletionStage<CosmosCheckpointPurgeResult> purgeAsync(
            CheckpointKey key, long expectedRevision, RunCancellation cancellation) {
        ValidationException validation = validatePurge(key, expectedRevision, cancellation);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> readHead(key, cancellation).thenCompose(head -> {
            if (head == null) {
                return inspectMissingPurgeHead(key, cancellation);
            }
            if (head.document.revision != expectedRevision) {
                return CompletableFuture.failedStage(CosmosSdkSupport.conflict(
                        "Checkpoint '" + key.value() + "'", expectedRevision, head.document.revision));
            }
            if (head.etag == null || head.etag.isBlank()) {
                return CompletableFuture.failedStage(
                        incompatible("Cosmos checkpoint head is missing its concurrency ETag."));
            }
            return maintenanceExpiry(head.document)
                    .thenCompose(expiresAt -> purgeNextBatch(
                            key,
                            expectedRevision,
                            new HeadRead(head.document, head.etag, expiresAt),
                            cancellation,
                            new PurgeProgress()));
        }));
    }

    @Override
    public CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> commitAsync(
            CheckpointCommit commit, long expectedRevision) {
        ValidationException validation = validateCommit(commit, expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> readHead(commit.key())
                .thenCompose(head -> prepareWrite(commit.key(), commit.checkpoint(), expectedRevision, head))
                .thenCompose(write -> readLedgerMutations(commit.ledgerDelta(), 0, new ArrayList<>())
                        .thenCompose(ledger -> executeCheckpointWrite(write, ledger)))
                .thenApply(write -> write.versioned));
    }

    /**
     * Loads one ledger entry written through an atomic checkpoint commit.
     *
     * @param invocationId invocation identifier
     * @return optional versioned detached entry
     */
    public CompletionStage<Optional<VersionedSnapshot<InvocationLedgerEntry>>> loadLedgerAsync(
            InvocationId invocationId) {
        if (invocationId == null) {
            return CompletableFuture.failedStage(new ValidationException("invocationId must not be null."));
        }
        return afterInitialization(() -> readLedger(invocationId).thenApply(read -> {
            if (read == null) {
                return Optional.empty();
            }
            InvocationLedgerEntry entry =
                    ledgerCodec.decode(read.document, ledgerId(invocationId), partitionKeyValue());
            return Optional.of(new VersionedSnapshot<>(entry, read.document.revision));
        }));
    }

    /**
     * Lists one bounded page of immutable checkpoint snapshots for the bound workflow.
     *
     * <p>Every returned document must contain the exact canonical snapshot sort key written by the
     * current format. Missing or malformed development snapshots are incompatible and must be
     * recreated; this storage does not migrate them online.
     *
     * @param cursor opaque partition-bound continuation cursor, or {@code null}
     * @return checkpoint page ordered by canonical revision and checkpoint ID
     */
    public CompletionStage<CosmosPage<VersionedSnapshot<WorkflowCheckpoint>>> listAsync(String cursor) {
        String continuation;
        try {
            continuation = CosmosCursorCodec.decode(partitionKeyValue(), cursor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        return afterInitialization(() -> CosmosSdkSupport.stage(
                        container
                                .queryItems(snapshotQuery(), queryOptions(), CosmosCheckpointDocument.class)
                                .byPage(continuation, options.pageSize())
                                .next(),
                        options.storage().client().retryOptions())
                .thenApply(page -> checkpointPage(page)));
    }

    @Override
    public CheckpointStorageDurability durability() {
        return CheckpointStorageDurability.DURABLE_BACKEND;
    }

    /**
     * Closes only a client created by {@link #create(CosmosCheckpointOptions,
     * JsonStateSerializer)}.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    private CompletionStage<CheckpointWrite> prepareWrite(
            CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision, HeadRead head) {
        if (head != null) {
            long retryRevision = expectedRevision == CREATE_ONLY
                    ? 1
                    : expectedRevision == Long.MAX_VALUE ? Long.MAX_VALUE : expectedRevision + 1;
            if (head.document.revision == retryRevision) {
                WorkflowCheckpoint retryCheckpoint = checkpoint.withRevision(retryRevision);
                byte[] retryPayload;
                try {
                    retryPayload = codec.encode(retryCheckpoint);
                } catch (RuntimeException exception) {
                    return CompletableFuture.failedStage(exception);
                }
                String retryDigest = CosmosSdkSupport.payloadDigest(retryPayload);
                if (retryDigest.equals(head.document.payloadDigest)) {
                    return CompletableFuture.completedStage(new CheckpointWrite(
                            key,
                            head,
                            head.document,
                            snapshotDocument(key, retryCheckpoint, retryPayload, retryDigest),
                            new VersionedSnapshot<>(retryCheckpoint, retryRevision),
                            expectedRevision,
                            true));
                }
            }
        }
        Long actual = head == null ? null : head.document.revision;
        boolean mismatch = expectedRevision == CREATE_ONLY ? head != null : head == null || actual != expectedRevision;
        if (mismatch) {
            return CompletableFuture.failedStage(
                    CosmosSdkSupport.conflict("Checkpoint '" + key.value() + "'", expectedRevision, actual));
        }
        long revision = head == null ? 1 : nextRevision(head.document.revision);
        WorkflowCheckpoint stored = checkpoint.withRevision(revision);
        byte[] payload;
        try {
            payload = codec.encode(stored);
            CosmosSdkSupport.encodePayload(payload, options.storage().maxDocumentBytes());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        String digest = CosmosSdkSupport.payloadDigest(payload);
        CosmosCheckpointDocument headDocument = headDocument(key, stored, payload, digest);
        CosmosCheckpointDocument snapshot = snapshotDocument(key, stored, payload, digest);
        return CompletableFuture.completedStage(new CheckpointWrite(
                key, head, headDocument, snapshot, new VersionedSnapshot<>(stored, revision), expectedRevision, false));
    }

    private CompletionStage<CheckpointWrite> executeCheckpointWrite(
            CheckpointWrite write, List<LedgerRead> ledgerReads) {
        if (write.idempotent) {
            if (ledgerReads.stream().allMatch(LedgerRead::idempotent)) {
                return CompletableFuture.completedStage(write);
            }
            return CompletableFuture.failedStage(new StorageConflictException(
                    "Idempotent checkpoint retry found an inconsistent invocation-ledger delta."));
        }
        CosmosBatch batch = CosmosBatch.createCosmosBatch(partitionKey());
        batch.createItemOperation(write.snapshot, new CosmosBatchItemRequestOptions().setIfNoneMatchETag("*"));
        if (write.head == null) {
            batch.createItemOperation(write.headDocument, new CosmosBatchItemRequestOptions().setIfNoneMatchETag("*"));
        } else {
            batch.replaceItemOperation(
                    write.headDocument.id,
                    write.headDocument,
                    new CosmosBatchItemRequestOptions().setIfMatchETag(write.head.etag));
        }
        for (LedgerRead ledger : ledgerReads) {
            if (ledger.idempotent) {
                continue;
            }
            CosmosLedgerDocument replacement = ledgerCodec.encode(
                    ledger.mutation.entry(),
                    ledger.nextRevision,
                    ledgerId(ledger.mutation.entry().invocationId()),
                    partitionKeyValue(),
                    options.timeToLiveSeconds());
            if (ledger.current == null) {
                batch.createItemOperation(replacement, new CosmosBatchItemRequestOptions().setIfNoneMatchETag("*"));
            } else {
                batch.replaceItemOperation(
                        replacement.id,
                        replacement,
                        new CosmosBatchItemRequestOptions().setIfMatchETag(ledger.current.etag));
            }
        }
        return executeBatch(batch, "commit", write.key, write.expectedRevision).thenApply(ignored -> write);
    }

    private CompletionStage<List<LedgerRead>> readLedgerMutations(
            InvocationLedgerDelta delta, int index, ArrayList<LedgerRead> reads) {
        if (index >= delta.mutations().size()) {
            return CompletableFuture.completedStage(List.copyOf(reads));
        }
        LedgerEntryMutation mutation = delta.mutations().get(index);
        return readLedger(mutation.entry().invocationId()).thenCompose(current -> {
            long expected = mutation.expectedRevision();
            Long actual = current == null ? null : current.document.revision;
            boolean mismatch = expected == 0 ? current != null : current == null || actual != expected;
            if (mismatch) {
                long retryRevision = expected == Long.MAX_VALUE ? Long.MAX_VALUE : expected + 1;
                if (current != null
                        && current.document.revision == retryRevision
                        && ledgerCodec
                                .decode(
                                        current.document,
                                        ledgerId(mutation.entry().invocationId()),
                                        partitionKeyValue())
                                .equals(mutation.entry())) {
                    reads.add(new LedgerRead(mutation, current, retryRevision, true));
                    return readLedgerMutations(delta, index + 1, reads);
                }
                return CompletableFuture.failedStage(CosmosSdkSupport.conflict(
                        "Invocation '" + mutation.entry().invocationId() + "'", expected, actual));
            }
            reads.add(new LedgerRead(mutation, current, current == null ? 1 : nextRevision(actual), false));
            return readLedgerMutations(delta, index + 1, reads);
        });
    }

    private CompletionStage<HeadRead> readHead(CheckpointKey key) {
        return readHead(key, new DefaultRunCancellation());
    }

    private CompletionStage<HeadRead> readHead(CheckpointKey key, RunCancellation cancellation) {
        return CosmosSdkSupport.stage(
                        container.readItem(headId(key), partitionKey(), CosmosCheckpointDocument.class),
                        options.storage().client().retryOptions(),
                        cancellation)
                .handle((response, failure) -> {
                    if (failure != null) {
                        if (CosmosSdkSupport.hasStatus(failure, 404)) {
                            return null;
                        }
                        throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
                    }
                    return new HeadRead(validateHead(key, response.getItem()), response.getETag(), null);
                });
    }

    private CompletionStage<CosmosCheckpointPurgeResult> inspectMissingPurgeHead(
            CheckpointKey key, RunCancellation cancellation) {
        CompletableFuture<CosmosCheckpointPurgeResult> result = new CompletableFuture<>();
        PurgeProgress progress = new PurgeProgress();
        queryPurgeRows(key, cancellation).whenComplete((rows, failure) -> {
            if (failure != null) {
                completePurgeFailure(result, progress, failure);
                return;
            }
            CosmosCheckpointPurgeResult.Status status = rows.isEmpty()
                    ? CosmosCheckpointPurgeResult.Status.ALREADY_PURGED
                    : CosmosCheckpointPurgeResult.Status.CONFLICT;
            result.complete(progress.result(status, null));
        });
        return result.minimalCompletionStage();
    }

    private CompletionStage<CosmosCheckpointPurgeResult> purgeNextBatch(
            CheckpointKey key,
            long expectedRevision,
            HeadRead head,
            RunCancellation cancellation,
            PurgeProgress progress) {
        CompletableFuture<CosmosCheckpointPurgeResult> result = new CompletableFuture<>();
        queryPurgeRows(key, cancellation).whenComplete((rows, failure) -> {
            if (failure != null) {
                completePurgeFailure(result, progress, failure);
                return;
            }
            CompletionStage<CosmosCheckpointPurgeResult> next = rows.isEmpty()
                    ? deletePurgeHead(key, expectedRevision, head, cancellation, progress)
                    : deletePurgeRows(key, expectedRevision, head, cancellation, progress, rows);
            forward(next, result);
        });
        return result.minimalCompletionStage();
    }

    private CompletionStage<List<CosmosCheckpointPurgeRow>> queryPurgeRows(
            CheckpointKey key, RunCancellation cancellation) {
        Flux<CosmosCheckpointPurgeRow> rows = container
                .queryItems(purgeQuery(key.value()), purgeQueryOptions(), CosmosCheckpointPurgeRow.class)
                .byPage(MAX_PURGE_DELETES_PER_BATCH)
                .concatMapIterable(page -> validatedPurgeRows(page.getResults(), key.value()))
                .take(MAX_PURGE_DELETES_PER_BATCH);
        return CosmosSdkSupport.stage(
                rows.collectList(), options.storage().client().retryOptions(), cancellation);
    }

    private CompletionStage<CosmosCheckpointPurgeResult> deletePurgeRows(
            CheckpointKey key,
            long expectedRevision,
            HeadRead head,
            RunCancellation cancellation,
            PurgeProgress progress,
            List<CosmosCheckpointPurgeRow> rows) {
        CosmosBatch batch = CosmosBatch.createCosmosBatch(partitionKey());
        head.document._ts = null;
        if (head.expiresAtEpochSeconds != null) {
            head.document.ttl = remainingTtl(head.expiresAtEpochSeconds);
        }
        batch.replaceItemOperation(
                head.document.id, head.document, new CosmosBatchItemRequestOptions().setIfMatchETag(head.etag));
        for (CosmosCheckpointPurgeRow row : rows) {
            batch.deleteItemOperation(row.id, new CosmosBatchItemRequestOptions().setIfMatchETag(row.etag));
        }
        CompletableFuture<CosmosCheckpointPurgeResult> result = new CompletableFuture<>();
        CosmosSdkSupport.stage(
                        container.executeCosmosBatch(batch),
                        options.storage().client().retryOptions(),
                        cancellation)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        completePurgeFailure(result, progress, failure);
                        return;
                    }
                    if (!response.isSuccessStatusCode()) {
                        int failureStatus = effectiveBatchStatus(response);
                        result.complete(progress.result(status(failureStatus), failureStatus));
                        return;
                    }
                    progress.completedBatches++;
                    progress.deletedSnapshots += rows.size();
                    String nextHeadEtag = batchHeadEtag(response);
                    if (nextHeadEtag == null || nextHeadEtag.isBlank()) {
                        result.complete(progress.result(CosmosCheckpointPurgeResult.Status.FAILED, null));
                        return;
                    }
                    HeadRead nextHead = new HeadRead(head.document, nextHeadEtag, head.expiresAtEpochSeconds);
                    forward(purgeNextBatch(key, expectedRevision, nextHead, cancellation, progress), result);
                });
        return result.minimalCompletionStage();
    }

    private CompletionStage<CosmosCheckpointPurgeResult> deletePurgeHead(
            CheckpointKey key,
            long expectedRevision,
            HeadRead head,
            RunCancellation cancellation,
            PurgeProgress progress) {
        CosmosBatch batch = CosmosBatch.createCosmosBatch(partitionKey());
        batch.deleteItemOperation(head.document.id, new CosmosBatchItemRequestOptions().setIfMatchETag(head.etag));
        CompletableFuture<CosmosCheckpointPurgeResult> result = new CompletableFuture<>();
        CosmosSdkSupport.stage(
                        container.executeCosmosBatch(batch),
                        options.storage().client().retryOptions(),
                        cancellation)
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        completePurgeFailure(result, progress, failure);
                        return;
                    }
                    if (!response.isSuccessStatusCode()) {
                        int failureStatus = effectiveBatchStatus(response);
                        result.complete(progress.result(status(failureStatus), failureStatus));
                        return;
                    }
                    progress.deletedHeads++;
                    progress.completedBatches++;
                    result.complete(progress.result(CosmosCheckpointPurgeResult.Status.COMPLETED, null));
                });
        return result.minimalCompletionStage();
    }

    private static List<CosmosCheckpointPurgeRow> validatedPurgeRows(
            List<CosmosCheckpointPurgeRow> rows, String checkpointKey) {
        if (rows == null) {
            throw incompatible("Cosmos checkpoint purge query returned a malformed projection.");
        }
        ArrayList<CosmosCheckpointPurgeRow> validated = new ArrayList<>();
        for (CosmosCheckpointPurgeRow row : rows) {
            if (row == null
                    || row.id == null
                    || row.id.isBlank()
                    || row.etag == null
                    || row.etag.isBlank()
                    || !SNAPSHOT_KIND.equals(row.kind)
                    || !checkpointKey.equals(row.checkpointKey)) {
                throw incompatible("Cosmos checkpoint purge query returned a malformed projection.");
            }
            validated.add(row);
        }
        return List.copyOf(validated);
    }

    private static void completePurgeFailure(
            CompletableFuture<CosmosCheckpointPurgeResult> result, PurgeProgress progress, Throwable failure) {
        Throwable cause = CosmosSdkSupport.unwrap(failure);
        if (cause instanceof RunCancelledException) {
            result.completeExceptionally(cause);
            return;
        }
        result.complete(progress.result(status(cause), statusCode(cause)));
    }

    private static CosmosCheckpointPurgeResult.Status status(Throwable failure) {
        if (failure instanceof StorageConflictException
                || CosmosSdkSupport.hasStatus(failure, 409)
                || CosmosSdkSupport.hasStatus(failure, 412)) {
            return CosmosCheckpointPurgeResult.Status.CONFLICT;
        }
        if (failure instanceof CosmosThrottledException || CosmosSdkSupport.hasStatus(failure, 429)) {
            return CosmosCheckpointPurgeResult.Status.THROTTLED;
        }
        return CosmosCheckpointPurgeResult.Status.FAILED;
    }

    private static CosmosCheckpointPurgeResult.Status status(int statusCode) {
        return switch (statusCode) {
            case 409, 412 -> CosmosCheckpointPurgeResult.Status.CONFLICT;
            case 429 -> CosmosCheckpointPurgeResult.Status.THROTTLED;
            default -> CosmosCheckpointPurgeResult.Status.FAILED;
        };
    }

    private static Integer statusCode(Throwable failure) {
        Throwable cause = CosmosSdkSupport.unwrap(failure);
        if (cause instanceof CosmosStorageException storage && storage.diagnostics() != null) {
            return storage.diagnostics().statusCode();
        }
        return null;
    }

    private static void forward(
            CompletionStage<CosmosCheckpointPurgeResult> source,
            CompletableFuture<CosmosCheckpointPurgeResult> target) {
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                target.complete(value);
            } else {
                target.completeExceptionally(CosmosSdkSupport.unwrap(failure));
            }
        });
    }

    private CompletionStage<LedgerPointRead> readLedger(InvocationId invocationId) {
        return CosmosSdkSupport.stage(
                        container.readItem(ledgerId(invocationId), partitionKey(), CosmosLedgerDocument.class),
                        options.storage().client().retryOptions())
                .handle((response, failure) -> {
                    if (failure != null) {
                        if (CosmosSdkSupport.hasStatus(failure, 404)) {
                            return null;
                        }
                        throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
                    }
                    ledgerCodec.decode(response.getItem(), ledgerId(invocationId), partitionKeyValue());
                    return new LedgerPointRead(response.getItem(), response.getETag());
                });
    }

    private CompletionStage<Void> executeBatch(
            CosmosBatch batch, String operation, CheckpointKey key, long expectedRevision) {
        return CosmosSdkSupport.stage(
                        container.executeCosmosBatch(batch),
                        options.storage().client().retryOptions())
                .thenCompose(response -> batchResult(response, operation, key, expectedRevision));
    }

    private CompletionStage<Void> batchResult(
            CosmosBatchResponse response, String operation, CheckpointKey key, long expectedRevision) {
        if (response.isSuccessStatusCode()) {
            return CompletableFuture.completedStage(null);
        }
        int failureStatus = effectiveBatchStatus(response);
        if (failureStatus == 409 || failureStatus == 412) {
            return CompletableFuture.failedStage(
                    CosmosSdkSupport.conflict("Checkpoint '" + key.value() + "'", expectedRevision, null));
        }
        CosmosOperationDiagnostics diagnostics = new CosmosOperationDiagnostics(
                failureStatus, response.getActivityId(), response.getRequestCharge(), response.getRetryAfterDuration());
        if (failureStatus == 429) {
            return CompletableFuture.failedStage(new CosmosThrottledException(null, diagnostics));
        }
        return CompletableFuture.failedStage(new CosmosStorageException(
                "Cosmos checkpoint " + operation + " failed with status " + failureStatus + ".",
                null,
                CosmosStorageException.Kind.SERVICE,
                diagnostics));
    }

    private static int effectiveBatchStatus(CosmosBatchResponse response) {
        int fallback = response.getStatusCode();
        if (response.getResults() == null) {
            return fallback;
        }
        int failedDependency = fallback;
        for (var operation : response.getResults()) {
            if (!operation.isSuccessStatusCode()) {
                if (operation.getStatusCode() != 424) {
                    return operation.getStatusCode();
                }
                failedDependency = operation.getStatusCode();
            }
        }
        return failedDependency;
    }

    private CompletionStage<Long> maintenanceExpiry(CosmosCheckpointDocument document) {
        if (document.ttl != null) {
            return CompletableFuture.completedStage(expiry(document.ttl, document._ts));
        }
        return effectiveDefaultTtl().thenApply(defaultTtl -> expiry(defaultTtl, document._ts));
    }

    private CompletionStage<Integer> effectiveDefaultTtl() {
        CosmosProvisioningOptions provisioning = options.storage().container().provisioning();
        if (provisioning.enabled()) {
            return CompletableFuture.completedStage(provisioning.defaultTimeToLiveSeconds());
        }
        CompletionStage<Integer> existing = defaultTtlPreparation.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Integer> completion = new CompletableFuture<>();
        CompletionStage<Integer> exposed = completion.minimalCompletionStage();
        if (!defaultTtlPreparation.compareAndSet(null, exposed)) {
            return effectiveDefaultTtl();
        }
        CosmosSdkSupport.stage(container.read(), options.storage().client().retryOptions())
                .whenComplete((response, failure) -> {
                    if (failure == null) {
                        try {
                            completion.complete(response.getProperties().getDefaultTimeToLiveInSeconds());
                        } catch (RuntimeException exception) {
                            defaultTtlPreparation.compareAndSet(exposed, null);
                            completion.completeExceptionally(exception);
                        }
                    } else {
                        defaultTtlPreparation.compareAndSet(exposed, null);
                        completion.completeExceptionally(CosmosSdkSupport.unwrap(failure));
                    }
                });
        return exposed;
    }

    private static Long expiry(Integer ttl, Long timestamp) {
        if (ttl == null || ttl == -1) {
            return null;
        }
        if (ttl <= 0 || timestamp == null || timestamp <= 0) {
            throw incompatible("Cosmos checkpoint TTL metadata is malformed.");
        }
        try {
            return Math.addExact(timestamp, ttl.longValue());
        } catch (ArithmeticException exception) {
            throw incompatible("Cosmos checkpoint TTL metadata is malformed.");
        }
    }

    private static int remainingTtl(long expiresAtEpochSeconds) {
        long remaining = expiresAtEpochSeconds - java.time.Instant.now().getEpochSecond();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, remaining));
    }

    private static String batchHeadEtag(CosmosBatchResponse response) {
        if (response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        return response.getResults().getFirst().getETag();
    }

    private CosmosPage<VersionedSnapshot<WorkflowCheckpoint>> checkpointPage(
            FeedResponse<CosmosCheckpointDocument> page) {
        ArrayList<VersionedSnapshot<WorkflowCheckpoint>> checkpoints = new ArrayList<>();
        for (CosmosCheckpointDocument document : page.getResults()) {
            CosmosCheckpointDocument checked = validateSnapshot(document);
            WorkflowCheckpoint checkpoint = decode(checked);
            checkpoints.add(new VersionedSnapshot<>(checkpoint, checked.revision));
        }
        return new CosmosPage<>(
                checkpoints,
                CosmosCursorCodec.encode(partitionKeyValue(), page.getContinuationToken()),
                CosmosSdkSupport.diagnostics(page));
    }

    private CosmosCheckpointDocument headDocument(
            CheckpointKey key, WorkflowCheckpoint checkpoint, byte[] payload, String digest) {
        CosmosCheckpointDocument document = checkpointDocument(key, checkpoint, payload, digest);
        document.id = headId(key);
        document.kind = HEAD_KIND;
        return document;
    }

    private CosmosCheckpointDocument snapshotDocument(
            CheckpointKey key, WorkflowCheckpoint checkpoint, byte[] payload, String digest) {
        CosmosCheckpointDocument document = checkpointDocument(key, checkpoint, payload, digest);
        document.id = snapshotId(checkpoint.checkpointId());
        document.kind = SNAPSHOT_KIND;
        return document;
    }

    private CosmosCheckpointDocument checkpointDocument(
            CheckpointKey key, WorkflowCheckpoint checkpoint, byte[] payload, String digest) {
        CosmosCheckpointDocument document = new CosmosCheckpointDocument();
        document.partitionKey = partitionKeyValue();
        document.schemaVersion = SCHEMA_VERSION;
        document.checkpointKey = key.value();
        document.workflowId = checkpoint.workflowId();
        document.checkpointId = checkpoint.checkpointId();
        document.revision = checkpoint.revision();
        document.snapshotSortKey = snapshotSortKey(checkpoint.revision(), checkpoint.checkpointId());
        document.payload =
                CosmosSdkSupport.encodePayload(payload, options.storage().maxDocumentBytes());
        document.payloadDigest = digest;
        document.ttl = options.timeToLiveSeconds();
        return document;
    }

    private WorkflowCheckpoint decode(CosmosCheckpointDocument document) {
        WorkflowCheckpoint checkpoint = codec.decode(CosmosSdkSupport.decodePayload(
                document.payload, options.storage().maxDocumentBytes()));
        if (!options.workflowId().equals(checkpoint.workflowId())
                || !document.checkpointId.equals(checkpoint.checkpointId())
                || document.revision != checkpoint.revision()) {
            throw incompatible("Stored Cosmos checkpoint payload identity does not match its document.");
        }
        return checkpoint;
    }

    private CosmosCheckpointDocument validateHead(CheckpointKey key, CosmosCheckpointDocument document) {
        if (!validCommon(document)
                || !headId(key).equals(document.id)
                || !HEAD_KIND.equals(document.kind)
                || !key.value().equals(document.checkpointKey)) {
            throw incompatible("Stored Cosmos checkpoint head is malformed or belongs to another key.");
        }
        return document;
    }

    private CosmosCheckpointDocument validateSnapshot(CosmosCheckpointDocument document) {
        if (!validCommon(document)
                || !snapshotId(document.checkpointId).equals(document.id)
                || !SNAPSHOT_KIND.equals(document.kind)) {
            throw incompatible("Stored Cosmos checkpoint snapshot is malformed or belongs to another partition.");
        }
        return document;
    }

    private boolean validCommon(CosmosCheckpointDocument document) {
        return document != null
                && partitionKeyValue().equals(document.partitionKey)
                && document.schemaVersion != null
                && document.schemaVersion == SCHEMA_VERSION
                && options.workflowId().equals(document.workflowId)
                && document.checkpointKey != null
                && document.checkpointId != null
                && document.revision != null
                && document.revision > 0
                && snapshotSortKey(document.revision, document.checkpointId).equals(document.snapshotSortKey)
                && document.payload != null
                && document.payloadDigest != null;
    }

    SqlQuerySpec snapshotQuery() {
        return new SqlQuerySpec(
                "SELECT * FROM c WHERE c.kind = @kind AND c.workflowId = @workflowId"
                        + " ORDER BY c.snapshotSortKey ASC",
                List.of(
                        new SqlParameter("@kind", SNAPSHOT_KIND),
                        new SqlParameter("@workflowId", options.workflowId())));
    }

    SqlQuerySpec purgeQuery(String checkpointKey) {
        return new SqlQuerySpec(
                "SELECT c.id, c.kind, c.checkpointKey, c._etag AS etag FROM c"
                        + " WHERE c.kind = @kind AND c.checkpointKey = @checkpointKey",
                List.of(new SqlParameter("@kind", SNAPSHOT_KIND), new SqlParameter("@checkpointKey", checkpointKey)));
    }

    CosmosQueryRequestOptions queryOptions() {
        return new CosmosQueryRequestOptions()
                .setPartitionKey(partitionKey())
                .setMaxDegreeOfParallelism(1)
                .setMaxBufferedItemCount(options.pageSize());
    }

    private CosmosQueryRequestOptions purgeQueryOptions() {
        return new CosmosQueryRequestOptions()
                .setPartitionKey(partitionKey())
                .setMaxDegreeOfParallelism(1)
                .setMaxBufferedItemCount(MAX_PURGE_DELETES_PER_BATCH);
    }

    private String headId(CheckpointKey key) {
        return CosmosSdkSupport.itemId("checkpoint-head", options.workflowId(), key.value());
    }

    private String snapshotId(String checkpointId) {
        return CosmosSdkSupport.itemId("workflow-checkpoint", options.workflowId(), checkpointId);
    }

    static String snapshotSortKey(long revision, String checkpointId) {
        return String.format(Locale.ROOT, "%019d:%s", revision, checkpointId);
    }

    private String ledgerId(InvocationId invocationId) {
        return CosmosSdkSupport.itemId("invocation-ledger", options.workflowId(), invocationId.value());
    }

    private PartitionKey partitionKey() {
        return new PartitionKey(partitionKeyValue());
    }

    private String partitionKeyValue() {
        return CosmosSdkSupport.partitionKey(options.storage().partition(), "workflow", options.workflowId());
    }

    private <T> CompletionStage<T> afterInitialization(java.util.function.Supplier<CompletionStage<T>> operation) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new CosmosStorageException(
                    "Cosmos checkpoint storage is closed.", null, CosmosStorageException.Kind.CLOSED, null));
        }
        return initialization.thenCompose(ignored -> operation.get());
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw incompatible("Cosmos storage revision is exhausted.");
        }
        return revision + 1;
    }

    private static CosmosStorageException incompatible(String message) {
        return new CosmosStorageException(message, null, CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE, null);
    }

    private static ValidationException validateKey(CheckpointKey key) {
        return key == null ? new ValidationException("key must not be null.") : null;
    }

    private ValidationException validateSave(CheckpointKey key, WorkflowCheckpoint checkpoint, long expectedRevision) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        if (checkpoint == null) {
            return new ValidationException("checkpoint must not be null.");
        }
        if (!options.workflowId().equals(checkpoint.workflowId())) {
            return new ValidationException("checkpoint.workflowId must match the workflow bound to this storage.");
        }
        if (expectedRevision != CREATE_ONLY && expectedRevision <= 0) {
            return new ValidationException("expectedRevision must be -1 for create-only or greater than zero.");
        }
        return null;
    }

    private static ValidationException validateDelete(CheckpointKey key, long expectedRevision) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        return expectedRevision <= 0
                ? new ValidationException("delete expectedRevision must be greater than zero.")
                : null;
    }

    private static ValidationException validatePurge(
            CheckpointKey key, long expectedRevision, RunCancellation cancellation) {
        ValidationException delete = validateDelete(key, expectedRevision);
        if (delete != null) {
            return delete;
        }
        return cancellation == null ? new ValidationException("cancellation must not be null.") : null;
    }

    private static RuntimeException purgeFailure(
            CheckpointKey key, long expectedRevision, CosmosCheckpointPurgeResult result) {
        CosmosOperationDiagnostics diagnostics = result.serviceStatusCode() == null
                ? null
                : new CosmosOperationDiagnostics(result.serviceStatusCode(), null, null, null);
        return switch (result.status()) {
            case CONFLICT -> CosmosSdkSupport.conflict("Checkpoint '" + key.value() + "'", expectedRevision, null);
            case THROTTLED -> new CosmosThrottledException(null, diagnostics);
            case FAILED ->
                new CosmosStorageException(
                        "Cosmos checkpoint purge stopped after " + result.completedBatches() + " completed batches.",
                        null,
                        CosmosStorageException.Kind.SERVICE,
                        diagnostics);
            case ALREADY_PURGED ->
                CosmosSdkSupport.conflict("Checkpoint '" + key.value() + "'", expectedRevision, null);
            case COMPLETED -> new IllegalStateException("Completed checkpoint purge cannot be mapped to a failure.");
        };
    }

    private ValidationException validateCommit(CheckpointCommit commit, long expectedRevision) {
        if (commit == null) {
            return new ValidationException("commit must not be null.");
        }
        ValidationException save = validateSave(commit.key(), commit.checkpoint(), expectedRevision);
        if (save != null) {
            return save;
        }
        return commit.ledgerDelta().mutations().size() > CosmosCheckpointOptions.MAX_LEDGER_MUTATIONS
                ? new ValidationException("ledgerDelta exceeds maximum atomic mutation count "
                        + CosmosCheckpointOptions.MAX_LEDGER_MUTATIONS
                        + ".")
                : null;
    }

    private record HeadRead(CosmosCheckpointDocument document, String etag, Long expiresAtEpochSeconds) {}

    private record LedgerPointRead(CosmosLedgerDocument document, String etag) {}

    private record LedgerRead(
            LedgerEntryMutation mutation, LedgerPointRead current, long nextRevision, boolean idempotent) {}

    private record CheckpointWrite(
            CheckpointKey key,
            HeadRead head,
            CosmosCheckpointDocument headDocument,
            CosmosCheckpointDocument snapshot,
            VersionedSnapshot<WorkflowCheckpoint> versioned,
            long expectedRevision,
            boolean idempotent) {}

    private static final class PurgeProgress {
        private int deletedHeads;

        private int deletedSnapshots;

        private int completedBatches;

        private CosmosCheckpointPurgeResult result(
                CosmosCheckpointPurgeResult.Status status, Integer serviceStatusCode) {
            return new CosmosCheckpointPurgeResult(
                    deletedHeads, deletedSnapshots, completedBatches, status, serviceStatusCode);
        }
    }
}
