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
import com.microsoft.agents.agents.AgentSessionCodec;
import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.AgentSessionStateBag;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stores ordered Java message envelopes in one tenant/session Cosmos logical partition.
 *
 * <p>Each append transaction conditionally advances a sequence-head ETag and creates deterministic
 * message IDs. Retrying the same run/message batch verifies existing payload digests and does not
 * duplicate history.
 */
public final class CosmosHistoryProvider implements HistoryProvider, AutoCloseable {
    private static final String MESSAGE_KIND = "history-message";

    private static final String HEAD_KIND = "history-head";

    private static final int SCHEMA_VERSION = 1;

    private final CosmosHistoryOptions options;

    private final AgentSessionCodec codec;

    private final CosmosAsyncClient client;

    private final CosmosAsyncContainer container;

    private final boolean ownsClient;

    private final CompletionStage<Void> initialization;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a provider that owns one resilient SDK client.
     *
     * @param options bounded history options
     * @param serializer safe versioned state serializer
     * @return Cosmos history provider
     */
    public static CosmosHistoryProvider create(CosmosHistoryOptions options, JsonStateSerializer serializer) {
        CosmosHistoryOptions checked = CosmosValidation.requireNonNull(options, "options");
        CosmosAsyncClient client = CosmosClientFactory.create(checked.storage().client());
        return new CosmosHistoryProvider(client, true, checked, serializer);
    }

    CosmosHistoryProvider(
            CosmosAsyncClient client,
            boolean ownsClient,
            CosmosHistoryOptions options,
            JsonStateSerializer serializer) {
        this.client = CosmosValidation.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.options = CosmosValidation.requireNonNull(options, "options");
        this.codec = new AgentSessionCodec(CosmosValidation.requireNonNull(serializer, "serializer"));
        this.container =
                CosmosContainerProvisioner.container(client, options.storage().container());
        this.initialization = CosmosContainerProvisioner.provisionAsync(client, options.storage());
    }

    @Override
    public String id() {
        return options.providerId();
    }

    @Override
    public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request) {
        ValidationException validation = validateRequest(request);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> {
            SqlQuerySpec query = messageQuery(null);
            CosmosQueryRequestOptions requestOptions =
                    queryOptions(request.session().sessionId());
            Flux<CosmosHistoryDocument> documents = container
                    .queryItems(query, requestOptions, CosmosHistoryDocument.class)
                    .byPage(options.pageSize())
                    .concatMapIterable(FeedResponse::getResults)
                    .take(options.maxMessagesToLoad());
            return CosmosSdkSupport.stage(
                            documents.collectList(),
                            options.storage().client().retryOptions(),
                            request.runContext().cancellation())
                    .thenApply(items -> decodeMessages(request.session().sessionId(), items));
        });
    }

    /**
     * Loads one bounded history page with an opaque partition-bound cursor.
     *
     * @param request provider request
     * @param cursor continuation cursor, or {@code null}
     * @return page in chronological order
     */
    public CompletionStage<CosmosPage<Message>> loadPageAsync(ContextProviderRequest request, String cursor) {
        ValidationException validation = validateRequest(request);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        String sessionId = request.session().sessionId();
        String partition = partitionKeyValue(sessionId);
        String continuation;
        try {
            continuation = CosmosCursorCodec.decode(partition, cursor);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        return afterInitialization(() -> CosmosSdkSupport.stage(
                        container
                                .queryItems(messageQuery(null), queryOptions(sessionId), CosmosHistoryDocument.class)
                                .byPage(continuation, options.pageSize())
                                .next(),
                        options.storage().client().retryOptions(),
                        request.runContext().cancellation())
                .thenApply(page -> new CosmosPage<>(
                        decodeMessages(sessionId, page.getResults()),
                        CosmosCursorCodec.encode(partition, page.getContinuationToken()),
                        CosmosSdkSupport.diagnostics(page))));
    }

    @Override
    public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages) {
        ValidationException validation = validateAppend(request, messages);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        if (messages.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        List<Message> safeMessages = List.copyOf(messages);
        List<CosmosHistoryDocument> drafts;
        try {
            drafts = drafts(request, safeMessages);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        return afterInitialization(() -> appendAttempt(request, drafts, 0));
    }

    /**
     * Closes only a client created by {@link #create(CosmosHistoryOptions,
     * JsonStateSerializer)}.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    private CompletionStage<Void> appendAttempt(
            ContextProviderRequest request, List<CosmosHistoryDocument> drafts, int attempt) {
        return inspectExisting(request, drafts).thenCompose(existingCount -> {
            if (existingCount == drafts.size()) {
                return CompletableFuture.completedStage(null);
            }
            if (existingCount != 0) {
                return CompletableFuture.failedStage(
                        new StorageConflictException("Cosmos history contains a partial or mismatched append batch."));
            }
            return readHead(request.session().sessionId())
                    .thenCompose(head -> executeAppendBatch(request, drafts, head))
                    .handle((ignored, failure) -> failure)
                    .thenCompose(failure -> {
                        if (failure == null) {
                            return CompletableFuture.completedStage(null);
                        }
                        Throwable cause = CosmosSdkSupport.unwrap(failure);
                        if ((cause instanceof ConcurrentHistoryWriteException
                                        || CosmosSdkSupport.hasStatus(cause, 409)
                                        || CosmosSdkSupport.hasStatus(cause, 412))
                                && attempt < options.maxConcurrencyRetries()) {
                            return appendAttempt(request, drafts, attempt + 1);
                        }
                        return CompletableFuture.failedStage(CosmosSdkSupport.mapFailure(cause));
                    });
        });
    }

    private CompletionStage<Integer> inspectExisting(
            ContextProviderRequest request, List<CosmosHistoryDocument> drafts) {
        Flux<Integer> checks = Flux.fromIterable(drafts)
                .flatMap(
                        draft -> container
                                .readItem(
                                        draft.id,
                                        partitionKey(request.session().sessionId()),
                                        CosmosHistoryDocument.class)
                                .map(response -> {
                                    CosmosHistoryDocument existing = validateMessageDocument(
                                            request.session().sessionId(), response.getItem());
                                    if (!draft.payloadDigest.equals(existing.payloadDigest)) {
                                        throw new StorageConflictException(
                                                "Cosmos history message ID is bound to another payload.");
                                    }
                                    return 1;
                                })
                                .onErrorResume(
                                        failure -> CosmosSdkSupport.hasStatus(failure, 404), ignored -> Mono.just(0)),
                        options.storage().maxConcurrentOperations());
        return CosmosSdkSupport.stage(
                        checks.reduce(0, Integer::sum),
                        options.storage().client().retryOptions(),
                        request.runContext().cancellation())
                .thenApply(Integer::intValue);
    }

    private CompletionStage<HeadRead> readHead(String sessionId) {
        return CosmosSdkSupport.stage(
                        container.readItem(headId(sessionId), partitionKey(sessionId), CosmosHistoryHeadDocument.class),
                        options.storage().client().retryOptions())
                .handle((response, failure) -> {
                    if (failure != null) {
                        if (CosmosSdkSupport.hasStatus(failure, 404)) {
                            return new HeadRead(null, null);
                        }
                        throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
                    }
                    CosmosHistoryHeadDocument head = validateHead(sessionId, response.getItem());
                    return new HeadRead(head, response.getETag());
                });
    }

    private CompletionStage<Void> executeAppendBatch(
            ContextProviderRequest request, List<CosmosHistoryDocument> drafts, HeadRead headRead) {
        String sessionId = request.session().sessionId();
        long start = headRead.document == null ? 0 : headRead.document.nextSequence;
        if (start > Long.MAX_VALUE - drafts.size()) {
            return CompletableFuture.failedStage(new CosmosStorageException(
                    "Cosmos history sequence is exhausted.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null));
        }
        CosmosHistoryHeadDocument replacement = head(sessionId, start + drafts.size());
        CosmosBatch batch = CosmosBatch.createCosmosBatch(partitionKey(sessionId));
        if (headRead.document == null) {
            batch.createItemOperation(replacement, new CosmosBatchItemRequestOptions().setIfNoneMatchETag("*"));
        } else {
            batch.replaceItemOperation(
                    replacement.id, replacement, new CosmosBatchItemRequestOptions().setIfMatchETag(headRead.etag));
        }
        for (int index = 0; index < drafts.size(); index++) {
            CosmosHistoryDocument draft = drafts.get(index);
            draft.sequence = start + index;
            batch.createItemOperation(draft, new CosmosBatchItemRequestOptions().setIfNoneMatchETag("*"));
        }
        return CosmosSdkSupport.stage(
                        container.executeCosmosBatch(batch),
                        options.storage().client().retryOptions(),
                        request.runContext().cancellation())
                .thenCompose(response -> batchResult(response));
    }

    private CompletionStage<Void> batchResult(CosmosBatchResponse response) {
        if (response.isSuccessStatusCode()) {
            return CompletableFuture.completedStage(null);
        }
        if (response.getStatusCode() == 409 || response.getStatusCode() == 412) {
            return CompletableFuture.failedStage(new ConcurrentHistoryWriteException());
        }
        CosmosOperationDiagnostics diagnostics = new CosmosOperationDiagnostics(
                response.getStatusCode(),
                response.getActivityId(),
                response.getRequestCharge(),
                response.getRetryAfterDuration());
        if (response.getStatusCode() == 429) {
            return CompletableFuture.failedStage(new CosmosThrottledException(null, diagnostics));
        }
        return CompletableFuture.failedStage(new CosmosStorageException(
                "Cosmos history transactional append failed with status " + response.getStatusCode() + ".",
                null,
                CosmosStorageException.Kind.SERVICE,
                diagnostics));
    }

    private List<CosmosHistoryDocument> drafts(ContextProviderRequest request, List<Message> messages) {
        requireBoundedIdentifier(request.runContext().runId(), "runId");
        ArrayList<CosmosHistoryDocument> documents = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            byte[] payload = codec.encode(new AgentSessionSnapshot(
                    request.session().sessionId(), List.of(message), AgentSessionStateBag.empty()));
            String digest = CosmosSdkSupport.payloadDigest(payload);
            String stableIdentity = message.messageId() == null
                    ? request.runContext().runId() + ":" + index + ":" + digest
                    : "message:" + message.messageId();
            CosmosHistoryDocument document = new CosmosHistoryDocument();
            document.id = CosmosSdkSupport.itemId(
                    "history-message",
                    options.storage().partition().agentId(),
                    request.session().sessionId(),
                    stableIdentity);
            document.partitionKey = partitionKeyValue(request.session().sessionId());
            document.kind = MESSAGE_KIND;
            document.schemaVersion = SCHEMA_VERSION;
            document.operationId = request.runContext().runId();
            document.messageId = message.messageId();
            document.payload =
                    CosmosSdkSupport.encodePayload(payload, options.storage().maxDocumentBytes());
            document.payloadDigest = digest;
            document.ttl = options.timeToLiveSeconds();
            documents.add(document);
        }
        return List.copyOf(documents);
    }

    private List<Message> decodeMessages(String sessionId, List<CosmosHistoryDocument> documents) {
        ArrayList<Message> result = new ArrayList<>(documents.size());
        long previous = -1;
        for (CosmosHistoryDocument document : documents) {
            CosmosHistoryDocument checked = validateMessageDocument(sessionId, document);
            if (checked.sequence <= previous) {
                throw new CosmosStorageException(
                        "Cosmos history sequence is not strictly increasing.",
                        null,
                        CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                        null);
            }
            previous = checked.sequence;
            AgentSessionSnapshot snapshot = codec.decode(CosmosSdkSupport.decodePayload(
                    checked.payload, options.storage().maxDocumentBytes()));
            if (!sessionId.equals(snapshot.sessionId()) || snapshot.messages().size() != 1) {
                throw new CosmosStorageException(
                        "Stored Cosmos history payload has an invalid session or message count.",
                        null,
                        CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                        null);
            }
            result.add(snapshot.messages().getFirst());
        }
        return List.copyOf(result);
    }

    private CosmosHistoryDocument validateMessageDocument(String sessionId, CosmosHistoryDocument document) {
        if (document == null
                || document.id == null
                || !partitionKeyValue(sessionId).equals(document.partitionKey)
                || !MESSAGE_KIND.equals(document.kind)
                || document.schemaVersion == null
                || document.schemaVersion != SCHEMA_VERSION
                || document.sequence == null
                || document.sequence < 0
                || document.operationId == null
                || document.operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096
                || document.payload == null
                || document.payloadDigest == null) {
            throw new CosmosStorageException(
                    "Stored Cosmos history document is malformed or belongs to another partition.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
        return document;
    }

    private static void requireBoundedIdentifier(String value, String name) {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            throw new ValidationException(name + " must not exceed 4096 UTF-8 bytes.");
        }
    }

    private CosmosHistoryHeadDocument validateHead(String sessionId, CosmosHistoryHeadDocument document) {
        if (document == null
                || !headId(sessionId).equals(document.id)
                || !partitionKeyValue(sessionId).equals(document.partitionKey)
                || !HEAD_KIND.equals(document.kind)
                || document.schemaVersion == null
                || document.schemaVersion != SCHEMA_VERSION
                || document.nextSequence == null
                || document.nextSequence < 0) {
            throw new CosmosStorageException(
                    "Stored Cosmos history head is malformed or belongs to another partition.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
        return document;
    }

    private CosmosHistoryHeadDocument head(String sessionId, long nextSequence) {
        CosmosHistoryHeadDocument head = new CosmosHistoryHeadDocument();
        head.id = headId(sessionId);
        head.partitionKey = partitionKeyValue(sessionId);
        head.kind = HEAD_KIND;
        head.schemaVersion = SCHEMA_VERSION;
        head.nextSequence = nextSequence;
        return head;
    }

    SqlQuerySpec messageQuery(Integer top) {
        String select = top == null ? "SELECT *" : "SELECT TOP @top *";
        ArrayList<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter("@kind", MESSAGE_KIND));
        if (top != null) {
            parameters.add(new SqlParameter("@top", top));
        }
        return new SqlQuerySpec(select + " FROM c WHERE c.kind = @kind" + " ORDER BY c.sequence ASC", parameters);
    }

    CosmosQueryRequestOptions queryOptions(String sessionId) {
        return new CosmosQueryRequestOptions()
                .setPartitionKey(partitionKey(sessionId))
                .setMaxDegreeOfParallelism(1)
                .setMaxBufferedItemCount(options.pageSize());
    }

    private String headId(String sessionId) {
        return CosmosSdkSupport.itemId(
                "history-head", options.storage().partition().agentId(), sessionId);
    }

    private PartitionKey partitionKey(String sessionId) {
        return new PartitionKey(partitionKeyValue(sessionId));
    }

    private String partitionKeyValue(String sessionId) {
        return CosmosSdkSupport.partitionKey(options.storage().partition(), "history", sessionId);
    }

    private <T> CompletionStage<T> afterInitialization(java.util.function.Supplier<CompletionStage<T>> operation) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new CosmosStorageException(
                    "Cosmos history provider is closed.", null, CosmosStorageException.Kind.CLOSED, null));
        }
        return initialization.thenCompose(ignored -> operation.get());
    }

    private static ValidationException validateRequest(ContextProviderRequest request) {
        return request == null ? new ValidationException("request must not be null.") : null;
    }

    private ValidationException validateAppend(ContextProviderRequest request, List<Message> messages) {
        if (request == null) {
            return new ValidationException("request must not be null.");
        }
        if (messages == null) {
            return new ValidationException("messages must not be null.");
        }
        if (messages.size() > options.maxAppendBatchSize()) {
            return new ValidationException(
                    "messages exceeds configured maxAppendBatchSize " + options.maxAppendBatchSize() + ".");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            return new ValidationException("messages must not contain null.");
        }
        return null;
    }

    private record HeadRead(CosmosHistoryHeadDocument document, String etag) {}

    private static final class ConcurrentHistoryWriteException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
