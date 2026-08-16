// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.microsoft.agents.agents.AgentSessionCodec;
import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.SessionKey;
import com.microsoft.agents.agents.SessionStore;
import com.microsoft.agents.agents.SessionStoreDurability;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores versioned Java agent-session envelopes in tenant-isolated Cosmos logical partitions.
 *
 * <p>Point reads and conditional writes use the SDK's partition key and ETag primitives. Soft delete
 * is the default and preserves a monotonic per-key framework revision through recreation. Explicit
 * hard delete removes that revision lineage.
 */
public final class CosmosSessionStore implements SessionStore, AutoCloseable {
    private static final String KIND = "agent-session";

    private static final int SCHEMA_VERSION = 1;

    private final CosmosSessionStoreOptions options;

    private final AgentSessionCodec codec;

    private final CosmosAsyncClient client;

    private final CosmosAsyncContainer container;

    private final boolean ownsClient;

    private final CompletionStage<Void> initialization;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a store that owns one resilient SDK client.
     *
     * @param options immutable storage options
     * @param serializer safe versioned state serializer
     * @return Cosmos session store
     */
    public static CosmosSessionStore create(CosmosSessionStoreOptions options, JsonStateSerializer serializer) {
        CosmosSessionStoreOptions checked = CosmosValidation.requireNonNull(options, "options");
        CosmosAsyncClient client = CosmosClientFactory.create(checked.storage().client());
        return new CosmosSessionStore(client, true, checked, serializer);
    }

    CosmosSessionStore(
            CosmosAsyncClient client,
            boolean ownsClient,
            CosmosSessionStoreOptions options,
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
    public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
        ValidationException validation = validateKey(key);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> loadDocument(key).handle((response, failure) -> {
            if (failure != null) {
                if (CosmosSdkSupport.hasStatus(failure, 404)) {
                    return Optional.<VersionedSnapshot<AgentSessionSnapshot>>empty();
                }
                throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
            }
            CosmosSessionDocument document = validateDocument(key, response.getItem());
            if (Boolean.TRUE.equals(document.deleted)) {
                return Optional.empty();
            }
            AgentSessionSnapshot snapshot = codec.decode(CosmosSdkSupport.decodePayload(
                    document.payload, options.storage().maxDocumentBytes()));
            if (!key.value().equals(snapshot.sessionId())) {
                throw new CosmosStorageException(
                        "Stored Cosmos session payload identity does not match its key.",
                        null,
                        CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                        null);
            }
            return Optional.of(new VersionedSnapshot<>(snapshot, document.revision));
        }));
    }

    @Override
    public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
            SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
        ValidationException validation = validateSave(key, snapshot, expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        byte[] payload;
        try {
            payload = codec.encode(snapshot);
            CosmosSdkSupport.encodePayload(payload, options.storage().maxDocumentBytes());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        String digest = CosmosSdkSupport.payloadDigest(payload);
        return afterInitialization(() -> loadDocument(key)
                .handle((response, failure) -> {
                    if (failure != null && !CosmosSdkSupport.hasStatus(failure, 404)) {
                        throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
                    }
                    return failure == null ? response : null;
                })
                .thenCompose(current -> saveAgainstCurrent(key, snapshot, payload, digest, expectedRevision, current)));
    }

    @Override
    public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
        ValidationException validation = validateDelete(key, expectedRevision);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> loadDocument(key)
                .handle((response, failure) -> {
                    if (failure != null) {
                        if (CosmosSdkSupport.hasStatus(failure, 404)) {
                            throw new java.util.concurrent.CompletionException(
                                    CosmosSdkSupport.conflict("Session '" + key.value() + "'", expectedRevision, null));
                        }
                        throw new java.util.concurrent.CompletionException(CosmosSdkSupport.mapFailure(failure));
                    }
                    return response;
                })
                .thenCompose(current -> deleteCurrent(key, expectedRevision, current)));
    }

    @Override
    public SessionStoreDurability durability() {
        return SessionStoreDurability.DURABLE_COMMIT;
    }

    /**
     * Closes only a client created by {@link #create(CosmosSessionStoreOptions,
     * JsonStateSerializer)}.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    private CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAgainstCurrent(
            SessionKey key,
            AgentSessionSnapshot snapshot,
            byte[] payload,
            String digest,
            long expectedRevision,
            CosmosItemResponse<CosmosSessionDocument> currentResponse) {
        CosmosSessionDocument current =
                currentResponse == null ? null : validateDocument(key, currentResponse.getItem());
        if (isIdempotentRetry(current, digest, expectedRevision)) {
            return CompletableFuture.completedStage(new VersionedSnapshot<>(snapshot, current.revision));
        }
        boolean absent = current == null || Boolean.TRUE.equals(current.deleted);
        if (expectedRevision == CREATE_ONLY ? !absent : absent || current.revision != expectedRevision) {
            return CompletableFuture.failedStage(CosmosSdkSupport.conflict(
                    "Session '" + key.value() + "'", expectedRevision, current == null ? null : current.revision));
        }
        long nextRevision = current == null ? 1 : nextRevision(current.revision);
        CosmosSessionDocument replacement = document(key, payload, digest, nextRevision, false);
        CompletionStage<CosmosItemResponse<CosmosSessionDocument>> write;
        if (currentResponse == null) {
            CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfNoneMatchETag("*");
            write = CosmosSdkSupport.stage(
                    container.createItem(replacement, partitionKey(key), request),
                    options.storage().client().retryOptions());
        } else {
            CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfMatchETag(currentResponse.getETag());
            write = CosmosSdkSupport.stage(
                    container.replaceItem(replacement, replacement.id, partitionKey(key), request),
                    options.storage().client().retryOptions());
        }
        return conflictMapped(write, key, expectedRevision, current == null ? null : current.revision)
                .thenApply(response -> new VersionedSnapshot<>(snapshot, nextRevision));
    }

    private CompletionStage<Void> deleteCurrent(
            SessionKey key, long expectedRevision, CosmosItemResponse<CosmosSessionDocument> response) {
        CosmosSessionDocument current = validateDocument(key, response.getItem());
        if (Boolean.TRUE.equals(current.deleted)
                && expectedRevision < Long.MAX_VALUE
                && current.revision == expectedRevision + 1) {
            return CompletableFuture.completedStage(null);
        }
        if (Boolean.TRUE.equals(current.deleted) || current.revision != expectedRevision) {
            return CompletableFuture.failedStage(
                    CosmosSdkSupport.conflict("Session '" + key.value() + "'", expectedRevision, current.revision));
        }
        CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfMatchETag(response.getETag());
        CompletionStage<?> operation;
        if (options.deletePolicy() == CosmosDeletePolicy.HARD) {
            operation = CosmosSdkSupport.stage(
                    container.deleteItem(current.id, partitionKey(key), request),
                    options.storage().client().retryOptions());
        } else {
            CosmosSessionDocument tombstone = document(key, null, null, nextRevision(current.revision), true);
            operation = CosmosSdkSupport.stage(
                    container.replaceItem(tombstone, tombstone.id, partitionKey(key), request),
                    options.storage().client().retryOptions());
        }
        return conflictMapped(operation, key, expectedRevision, current.revision)
                .thenApply(ignored -> null);
    }

    private CompletionStage<CosmosItemResponse<CosmosSessionDocument>> loadDocument(SessionKey key) {
        return CosmosSdkSupport.stage(
                container.readItem(itemId(key), partitionKey(key), CosmosSessionDocument.class),
                options.storage().client().retryOptions());
    }

    private <T> CompletionStage<T> afterInitialization(java.util.function.Supplier<CompletionStage<T>> operation) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new CosmosStorageException(
                    "Cosmos session store is closed.", null, CosmosStorageException.Kind.CLOSED, null));
        }
        return initialization.thenCompose(ignored -> operation.get());
    }

    private <T> CompletionStage<T> conflictMapped(
            CompletionStage<T> operation, SessionKey key, long expectedRevision, Long actual) {
        CompletableFuture<T> result = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else if (CosmosSdkSupport.hasStatus(failure, 409) || CosmosSdkSupport.hasStatus(failure, 412)) {
                result.completeExceptionally(
                        CosmosSdkSupport.conflict("Session '" + key.value() + "'", expectedRevision, actual));
            } else {
                result.completeExceptionally(CosmosSdkSupport.mapFailure(failure));
            }
        });
        return result.minimalCompletionStage();
    }

    private CosmosSessionDocument document(
            SessionKey key, byte[] payload, String digest, long revision, boolean deleted) {
        CosmosSessionDocument document = new CosmosSessionDocument();
        document.id = itemId(key);
        document.partitionKey = partitionKeyValue(key);
        document.kind = KIND;
        document.schemaVersion = SCHEMA_VERSION;
        document.revision = revision;
        document.deleted = deleted;
        document.payload = payload == null
                ? null
                : CosmosSdkSupport.encodePayload(payload, options.storage().maxDocumentBytes());
        document.payloadDigest = digest;
        document.ttl = deleted ? options.tombstoneTimeToLiveSeconds() : options.timeToLiveSeconds();
        return document;
    }

    private CosmosSessionDocument validateDocument(SessionKey key, CosmosSessionDocument document) {
        if (document == null
                || !itemId(key).equals(document.id)
                || !partitionKeyValue(key).equals(document.partitionKey)
                || !KIND.equals(document.kind)
                || document.schemaVersion == null
                || document.schemaVersion != SCHEMA_VERSION
                || document.revision == null
                || document.revision <= 0
                || document.deleted == null
                || (!document.deleted && (document.payload == null || document.payloadDigest == null))) {
            throw new CosmosStorageException(
                    "Stored Cosmos session document is malformed or belongs to another partition.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
        return document;
    }

    private boolean isIdempotentRetry(CosmosSessionDocument current, String digest, long expectedRevision) {
        if (current == null || Boolean.TRUE.equals(current.deleted)) {
            return false;
        }
        return digest.equals(current.payloadDigest)
                && ((expectedRevision == CREATE_ONLY && current.revision == 1)
                        || (expectedRevision > 0
                                && expectedRevision < Long.MAX_VALUE
                                && current.revision == expectedRevision + 1));
    }

    private String itemId(SessionKey key) {
        return CosmosSdkSupport.itemId("session", options.storage().partition().agentId(), key.value());
    }

    private PartitionKey partitionKey(SessionKey key) {
        return new PartitionKey(partitionKeyValue(key));
    }

    private String partitionKeyValue(SessionKey key) {
        return CosmosSdkSupport.partitionKey(options.storage().partition(), "session", key.value());
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw new CosmosStorageException(
                    "Cosmos session revision is exhausted.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
        return revision + 1;
    }

    private static ValidationException validateKey(SessionKey key) {
        return key == null ? new ValidationException("key must not be null.") : null;
    }

    private static ValidationException validateSave(
            SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        if (snapshot == null) {
            return new ValidationException("snapshot must not be null.");
        }
        if (!key.value().equals(snapshot.sessionId())) {
            return new ValidationException("Session key must exactly match snapshot.sessionId.");
        }
        if (expectedRevision != CREATE_ONLY && expectedRevision <= 0) {
            return new ValidationException("expectedRevision must be -1 for create or greater than zero.");
        }
        return null;
    }

    private static ValidationException validateDelete(SessionKey key, long expectedRevision) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        return expectedRevision <= 0
                ? new ValidationException("delete expectedRevision must be greater than zero.")
                : null;
    }
}
