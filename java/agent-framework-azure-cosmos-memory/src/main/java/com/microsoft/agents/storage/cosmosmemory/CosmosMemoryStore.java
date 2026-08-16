// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.agents.memory.MemoryFilter;
import com.microsoft.agents.agents.memory.MemoryKey;
import com.microsoft.agents.agents.memory.MemoryListRequest;
import com.microsoft.agents.agents.memory.MemoryMetadata;
import com.microsoft.agents.agents.memory.MemoryPage;
import com.microsoft.agents.agents.memory.MemoryProvenance;
import com.microsoft.agents.agents.memory.MemoryQuery;
import com.microsoft.agents.agents.memory.MemoryRecord;
import com.microsoft.agents.agents.memory.MemoryScope;
import com.microsoft.agents.agents.memory.MemorySearchMode;
import com.microsoft.agents.agents.memory.MemorySearchResult;
import com.microsoft.agents.agents.memory.MemoryStore;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.storage.cosmos.CosmosStorageException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;

/**
 * Implements tenant-scoped Cosmos memory CRUD, full-text, vector, and hybrid search.
 *
 * <p>Every point request and query supplies one normalized logical partition. Tenant/scope filters
 * are routing invariants and are never caller-overridable SQL fragments. Metadata filters and search
 * terms are represented only by {@link SqlParameter} values.
 */
public final class CosmosMemoryStore implements MemoryStore, AutoCloseable {
    private static final String KIND = "memory";

    private static final int SCHEMA_VERSION = 1;

    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]*");

    private final CosmosMemoryOptions options;

    private final CosmosAsyncClient client;

    private final CosmosAsyncContainer container;

    private final boolean ownsClient;

    private final CompletionStage<Void> initialization;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a memory store that owns one resilient SDK client.
     *
     * @param options immutable vector, search, storage, and fallback options
     * @return Cosmos memory store
     */
    public static CosmosMemoryStore create(CosmosMemoryOptions options) {
        CosmosMemoryOptions checked = java.util.Objects.requireNonNull(options, "options");
        CosmosAsyncClient client =
                CosmosMemoryClientFactory.create(checked.storage().client());
        return new CosmosMemoryStore(client, true, checked);
    }

    CosmosMemoryStore(CosmosAsyncClient client, boolean ownsClient, CosmosMemoryOptions options) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.container = CosmosMemoryProvisioner.container(client, options);
        this.initialization = CosmosMemoryProvisioner.provisionAsync(client, options);
    }

    @Override
    public CompletionStage<VersionedSnapshot<MemoryRecord>> putAsync(
            MemoryRecord record, RunCancellation cancellation) {
        return upsertAsync(record, CREATE_ONLY, cancellation);
    }

    @Override
    public CompletionStage<VersionedSnapshot<MemoryRecord>> upsertAsync(
            MemoryRecord record, long expectedRevision, RunCancellation cancellation) {
        ValidationException validation;
        try {
            validation = validateUpsert(record, expectedRevision, cancellation);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> read(record.key(), cancellation)
                .thenCompose(current -> write(record, expectedRevision, current, cancellation)));
    }

    @Override
    public CompletionStage<Optional<VersionedSnapshot<MemoryRecord>>> getAsync(
            MemoryKey key, RunCancellation cancellation) {
        ValidationException validation = validateKey(key, cancellation);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> read(key, cancellation).thenApply(current -> {
            if (current == null) {
                return Optional.empty();
            }
            CosmosMemoryDocument document = validateDocument(key.scope(), key.memoryId(), current.getItem());
            return Optional.of(new VersionedSnapshot<>(decode(key.scope(), document), document.revision));
        }));
    }

    @Override
    public CompletionStage<Void> deleteAsync(MemoryKey key, long expectedRevision, RunCancellation cancellation) {
        ValidationException validation = validateDelete(key, expectedRevision, cancellation);
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        return afterInitialization(() -> read(key, cancellation).thenCompose(current -> {
            if (current == null) {
                return CompletableFuture.failedStage(
                        CosmosMemorySdkSupport.conflict(key.memoryId(), expectedRevision, null));
            }
            CosmosMemoryDocument document = validateDocument(key.scope(), key.memoryId(), current.getItem());
            if (document.revision != expectedRevision) {
                return CompletableFuture.failedStage(
                        CosmosMemorySdkSupport.conflict(key.memoryId(), expectedRevision, document.revision));
            }
            CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfMatchETag(current.getETag());
            CompletionStage<?> operation = CosmosMemorySdkSupport.stage(
                    container.deleteItem(document.id, partitionKey(key.scope()), request),
                    options.storage().client().retryOptions(),
                    cancellation);
            return conflictMapped(operation, key.memoryId(), expectedRevision, document.revision)
                    .thenApply(ignored -> null);
        }));
    }

    @Override
    public CompletionStage<MemoryPage<VersionedSnapshot<MemoryRecord>>> listAsync(
            MemoryListRequest request, RunCancellation cancellation) {
        ValidationException validation;
        try {
            validation = validateList(request, cancellation);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        String partition = partitionKeyValue(request.scope());
        String continuation;
        try {
            continuation = CosmosMemoryCursor.decode(partition, request.cursor());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        QueryPlan plan = listPlan(request.filter(), null);
        return afterInitialization(() -> CosmosMemorySdkSupport.stage(
                        container
                                .queryItems(plan.query, queryOptions(request.scope()), CosmosMemoryDocument.class)
                                .byPage(continuation, request.pageSize())
                                .next(),
                        options.storage().client().retryOptions(),
                        cancellation)
                .thenApply(page -> {
                    ArrayList<VersionedSnapshot<MemoryRecord>> records = new ArrayList<>();
                    for (CosmosMemoryDocument item : page.getResults()) {
                        CosmosMemoryDocument checked = validateDocument(request.scope(), item.memoryId, item);
                        records.add(new VersionedSnapshot<>(decode(request.scope(), checked), checked.revision));
                    }
                    return new MemoryPage<>(records, CosmosMemoryCursor.encode(partition, page.getContinuationToken()));
                }));
    }

    @Override
    public CompletionStage<MemoryPage<MemorySearchResult>> searchAsync(
            MemoryQuery query, RunCancellation cancellation) {
        ValidationException validation;
        try {
            validation = validateSearch(query, cancellation);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        if (validation != null) {
            return CompletableFuture.failedStage(validation);
        }
        if (requiresFullText(query.mode()) && !options.fullTextEnabled()) {
            if (options.fallback() == CosmosMemoryFallback.BOUNDED_PARTITION_SCAN) {
                return fallbackSearch(query, cancellation);
            }
            return CompletableFuture.failedStage(
                    new ValidationException("Full-text search is disabled for this Cosmos memory store."));
        }
        SearchPlan plan;
        try {
            plan = searchPlan(query);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        CompletionStage<MemoryPage<MemorySearchResult>> server = afterInitialization(() -> {
            Flux<CosmosMemorySearchRow> rows = container
                    .queryItems(plan.query, queryOptions(query.scope()), CosmosMemorySearchRow.class)
                    .byPage(query.topK())
                    .concatMapIterable(FeedResponse::getResults)
                    .take(query.topK());
            return CosmosMemorySdkSupport.stage(
                            rows.collectList(), options.storage().client().retryOptions(), cancellation)
                    .thenApply(items -> mapSearchRows(query, plan, items));
        });
        return recoverSearch(server, query, cancellation);
    }

    /**
     * Closes only a client created by {@link #create(CosmosMemoryOptions)}.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            client.close();
        }
    }

    private CompletionStage<VersionedSnapshot<MemoryRecord>> write(
            MemoryRecord record,
            long expectedRevision,
            CosmosItemResponse<CosmosMemoryDocument> currentResponse,
            RunCancellation cancellation) {
        CosmosMemoryDocument current = currentResponse == null
                ? null
                : validateDocument(record.key().scope(), record.key().memoryId(), currentResponse.getItem());
        String digest = CosmosMemorySdkSupport.recordDigest(record);
        if (isIdempotentRetry(current, digest, expectedRevision)) {
            return CompletableFuture.completedStage(new VersionedSnapshot<>(record, current.revision));
        }
        boolean mismatch = expectedRevision == CREATE_ONLY
                ? current != null
                : current == null || current.revision != expectedRevision;
        if (mismatch) {
            return CompletableFuture.failedStage(CosmosMemorySdkSupport.conflict(
                    record.key().memoryId(), expectedRevision, current == null ? null : current.revision));
        }
        long revision = current == null ? 1 : nextRevision(current.revision);
        CosmosMemoryDocument replacement = encode(record, revision, digest);
        CompletionStage<CosmosItemResponse<CosmosMemoryDocument>> operation;
        if (currentResponse == null) {
            CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfNoneMatchETag("*");
            operation = CosmosMemorySdkSupport.stage(
                    container.createItem(replacement, partitionKey(record.key().scope()), request),
                    options.storage().client().retryOptions(),
                    cancellation);
        } else {
            CosmosItemRequestOptions request = new CosmosItemRequestOptions().setIfMatchETag(currentResponse.getETag());
            operation = CosmosMemorySdkSupport.stage(
                    container.replaceItem(
                            replacement,
                            replacement.id,
                            partitionKey(record.key().scope()),
                            request),
                    options.storage().client().retryOptions(),
                    cancellation);
        }
        return conflictMapped(
                        operation, record.key().memoryId(), expectedRevision, current == null ? null : current.revision)
                .thenApply(ignored -> new VersionedSnapshot<>(record, revision));
    }

    private CompletionStage<CosmosItemResponse<CosmosMemoryDocument>> read(
            MemoryKey key, RunCancellation cancellation) {
        return CosmosMemorySdkSupport.stage(
                        container.readItem(
                                CosmosMemorySdkSupport.itemId(options, key.scope(), key.memoryId()),
                                partitionKey(key.scope()),
                                CosmosMemoryDocument.class),
                        options.storage().client().retryOptions(),
                        cancellation)
                .handle((response, failure) -> {
                    if (failure != null) {
                        if (CosmosMemorySdkSupport.hasStatus(failure, 404)) {
                            return null;
                        }
                        throw new java.util.concurrent.CompletionException(CosmosMemorySdkSupport.mapFailure(failure));
                    }
                    return response;
                });
    }

    private CosmosMemoryDocument encode(MemoryRecord record, long revision, String digest) {
        CosmosMemoryDocument document = new CosmosMemoryDocument();
        document.id = CosmosMemorySdkSupport.itemId(options, record);
        document.partitionKey = partitionKeyValue(record.key().scope());
        document.kind = KIND;
        document.schemaVersion = SCHEMA_VERSION;
        document.revision = revision;
        document.tenantDigest = CosmosMemorySdkSupport.tenantDigest(options);
        document.scopeDigest = CosmosMemorySdkSupport.scopeDigest(record.key().scope());
        document.memoryId = record.key().memoryId();
        document.content = record.content();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        ArrayList<Map<String, Object>> pairs = new ArrayList<>();
        record.metadata().values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Object value = MemoryStateValueMapper.toObject(entry.getValue());
                    metadata.put(entry.getKey(), value);
                    LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
                    pair.put("key", entry.getKey());
                    pair.put("value", value);
                    pairs.add(java.util.Collections.unmodifiableMap(pair));
                });
        document.metadata = java.util.Collections.unmodifiableMap(metadata);
        document.metadataPairs = List.copyOf(pairs);
        document.vector = record.embedding() == null ? null : record.embedding().values();
        document.vectorDimensions = options.vector().dimensions();
        document.vectorDataType = options.vector().dataType().value();
        document.vectorIndexType = options.vector().indexType().value();
        document.createdAt = record.createdAt().toString();
        document.updatedAt = record.updatedAt().toString();
        document.payloadDigest = digest;
        document.ttl = record.timeToLiveSeconds() == null ? options.timeToLiveSeconds() : record.timeToLiveSeconds();
        return document;
    }

    private MemoryRecord decode(MemoryScope scope, CosmosMemoryDocument document) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        document.metadata.forEach((key, value) -> metadata.put(key, MemoryStateValueMapper.fromObject(value)));
        EmbeddingVector embedding = document.vector == null ? null : new EmbeddingVector(document.vector);
        try {
            return new MemoryRecord(
                    new MemoryKey(scope, document.memoryId),
                    document.content,
                    new MemoryMetadata(metadata),
                    embedding,
                    Instant.parse(document.createdAt),
                    Instant.parse(document.updatedAt),
                    document.ttl);
        } catch (RuntimeException exception) {
            throw incompatible("Stored Cosmos memory timestamps or record fields are malformed.", exception);
        }
    }

    private CosmosMemoryDocument validateDocument(MemoryScope scope, String memoryId, CosmosMemoryDocument document) {
        if (document == null
                || memoryId == null
                || document.memoryId == null
                || document.content == null
                || document.metadata == null
                || document.metadataPairs == null) {
            throw incompatible("Stored Cosmos memory document is malformed.", null);
        }
        if (!CosmosMemorySdkSupport.itemId(options, scope, memoryId).equals(document.id)
                || !partitionKeyValue(scope).equals(document.partitionKey)
                || !KIND.equals(document.kind)
                || document.schemaVersion == null
                || document.schemaVersion != SCHEMA_VERSION
                || document.revision == null
                || document.revision <= 0
                || !CosmosMemorySdkSupport.tenantDigest(options).equals(document.tenantDigest)
                || !CosmosMemorySdkSupport.scopeDigest(scope).equals(document.scopeDigest)
                || !memoryId.equals(document.memoryId)
                || document.vectorDimensions == null
                || document.vectorDimensions != options.vector().dimensions()
                || !options.vector().dataType().value().equals(document.vectorDataType)
                || !options.vector().indexType().value().equals(document.vectorIndexType)
                || document.createdAt == null
                || document.updatedAt == null
                || document.payloadDigest == null
                || (document.vector != null
                        && document.vector.size() != options.vector().dimensions())) {
            throw incompatible(
                    "Stored Cosmos memory document is malformed, cross-scope, or has an incompatible vector contract.",
                    null);
        }
        validateStoredSize(document);
        validateMetadataPairs(document);
        return document;
    }

    private QueryPlan listPlan(MemoryFilter filter, Integer top) {
        ArrayList<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter("@kind", KIND));
        StringBuilder query = new StringBuilder(top == null ? "SELECT * FROM c" : "SELECT TOP @limit * FROM c");
        if (top != null) {
            parameters.add(new SqlParameter("@limit", top));
        }
        query.append(" WHERE c.kind = @kind");
        appendFilters(query, parameters, filter);
        query.append(" ORDER BY c.updatedAt DESC, c.id ASC");
        return new QueryPlan(new SqlQuerySpec(query.toString(), parameters), false);
    }

    SearchPlan searchPlan(MemoryQuery query) {
        List<String> terms = requiresFullText(query.mode()) ? terms(query.text()) : List.of();
        ArrayList<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter("@top", query.topK()));
        parameters.add(new SqlParameter("@kind", KIND));
        if (query.embedding() != null) {
            parameters.add(new SqlParameter("@vector", query.embedding().values()));
        }
        for (int index = 0; index < terms.size(); index++) {
            parameters.add(new SqlParameter("@term" + index, terms.get(index)));
        }
        String fullTextScore = terms.isEmpty()
                ? null
                : "FullTextScore(c.content,"
                        + java.util.stream.IntStream.range(0, terms.size())
                                .mapToObj(index -> "@term" + index)
                                .collect(java.util.stream.Collectors.joining(","))
                        + ")";
        String fullTextContains = terms.isEmpty()
                ? null
                : "FullTextContainsAny(c.content,"
                        + java.util.stream.IntStream.range(0, terms.size())
                                .mapToObj(index -> "@term" + index)
                                .collect(java.util.stream.Collectors.joining(","))
                        + ")";
        String vectorDistance = "VectorDistance(c.vector,@vector)";
        String select =
                switch (query.mode()) {
                    case VECTOR -> "SELECT TOP @top c, " + vectorDistance + " AS score FROM c";
                    case FULL_TEXT, HYBRID -> "SELECT TOP @top c FROM c";
                };
        StringBuilder sql = new StringBuilder(select).append(" WHERE c.kind = @kind");
        if (query.mode() != MemorySearchMode.FULL_TEXT) {
            sql.append(" AND IS_ARRAY(c.vector)");
        }
        if (fullTextContains != null) {
            sql.append(" AND ").append(fullTextContains);
        }
        appendFilters(sql, parameters, query.filter());
        switch (query.mode()) {
            case VECTOR -> sql.append(" ORDER BY ").append(vectorDistance);
            case FULL_TEXT -> sql.append(" ORDER BY RANK ").append(fullTextScore);
            case HYBRID ->
                sql.append(" ORDER BY RANK RRF(")
                        .append(vectorDistance)
                        .append(",")
                        .append(fullTextScore)
                        .append(")");
        }
        return new SearchPlan(new SqlQuerySpec(sql.toString(), parameters), query.mode() == MemorySearchMode.VECTOR);
    }

    private void appendFilters(StringBuilder query, List<SqlParameter> parameters, MemoryFilter filter) {
        int index = 0;
        for (Map.Entry<String, StateValue> entry : filter.equals().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            String parameter = "@filter" + index++;
            query.append(" AND ARRAY_CONTAINS(c.metadataPairs,")
                    .append(parameter)
                    .append(",true)");
            LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
            pair.put("key", entry.getKey());
            pair.put("value", MemoryStateValueMapper.toObject(entry.getValue()));
            parameters.add(new SqlParameter(parameter, java.util.Collections.unmodifiableMap(pair)));
        }
    }

    MemoryPage<MemorySearchResult> mapSearchPage(
            MemoryQuery query, SearchPlan plan, FeedResponse<CosmosMemorySearchRow> page) {
        return mapSearchRows(query, plan, page.getResults());
    }

    private MemoryPage<MemorySearchResult> mapSearchRows(
            MemoryQuery query, SearchPlan plan, List<CosmosMemorySearchRow> rows) {
        ArrayList<MemorySearchResult> results = new ArrayList<>();
        int rank = 1;
        for (CosmosMemorySearchRow row : rows) {
            if (row == null || row.c == null) {
                throw incompatible("Cosmos memory search returned a malformed projection.", null);
            }
            CosmosMemoryDocument document = validateDocument(query.scope(), row.c.memoryId, row.c);
            MemoryRecord record = decode(query.scope(), document);
            if (plan.serverScore) {
                results.add(new MemorySearchResult(record, serverScore(row.score), rank, provenance(record)));
            } else {
                results.add(MemorySearchResult.ranked(record, rank, provenance(record)));
            }
            rank++;
        }
        return new MemoryPage<>(results, null);
    }

    private CompletionStage<MemoryPage<MemorySearchResult>> recoverSearch(
            CompletionStage<MemoryPage<MemorySearchResult>> server, MemoryQuery query, RunCancellation cancellation) {
        CompletableFuture<MemoryPage<MemorySearchResult>> result = new CompletableFuture<>();
        server.whenComplete((page, failure) -> {
            if (failure == null) {
                result.complete(page);
                return;
            }
            Throwable cause = CosmosMemorySdkSupport.unwrap(failure);
            boolean capabilityFailure =
                    CosmosMemorySdkSupport.hasStatus(cause, 400) || CosmosMemorySdkSupport.hasStatus(cause, 501);
            if (options.fallback() != CosmosMemoryFallback.BOUNDED_PARTITION_SCAN || !capabilityFailure) {
                result.completeExceptionally(CosmosMemorySdkSupport.mapFailure(cause));
                return;
            }
            fallbackSearch(query, cancellation).whenComplete((fallback, fallbackFailure) -> {
                if (fallbackFailure == null) {
                    result.complete(fallback);
                } else {
                    result.completeExceptionally(CosmosMemorySdkSupport.unwrap(fallbackFailure));
                }
            });
        });
        return result.minimalCompletionStage();
    }

    private CompletionStage<MemoryPage<MemorySearchResult>> fallbackSearch(
            MemoryQuery query, RunCancellation cancellation) {
        QueryPlan plan = listPlan(query.filter(), options.fallbackMaxDocuments());
        return afterInitialization(() -> {
            Flux<CosmosMemoryDocument> documents = container
                    .queryItems(plan.query, queryOptions(query.scope()), CosmosMemoryDocument.class)
                    .byPage(options.pageSize())
                    .concatMapIterable(FeedResponse::getResults)
                    .take(options.fallbackMaxDocuments());
            return CosmosMemorySdkSupport.stage(
                            documents.collectList(), options.storage().client().retryOptions(), cancellation)
                    .thenApply(items -> rankFallback(query, items));
        });
    }

    private MemoryPage<MemorySearchResult> rankFallback(MemoryQuery query, List<CosmosMemoryDocument> documents) {
        List<String> queryTerms = query.text() == null ? List.of() : terms(query.text());
        ArrayList<FallbackScore> scored = new ArrayList<>();
        for (CosmosMemoryDocument raw : documents) {
            CosmosMemoryDocument document = validateDocument(query.scope(), raw.memoryId, raw);
            double textScore = queryTerms.isEmpty() ? 0 : lexicalScore(document.content, queryTerms);
            double vectorScore = query.embedding() == null || document.vector == null
                    ? 0
                    : vectorScore(query.embedding().values(), document.vector);
            double score =
                    switch (query.mode()) {
                        case FULL_TEXT -> textScore;
                        case VECTOR -> vectorScore;
                        case HYBRID -> (textScore + vectorScore) / 2.0;
                    };
            if (score > 0) {
                scored.add(new FallbackScore(document, score));
            }
        }
        scored.sort(
                Comparator.comparingDouble(FallbackScore::score).reversed().thenComparing(item -> item.document.id));
        ArrayList<MemorySearchResult> results = new ArrayList<>();
        int limit = Math.min(query.topK(), scored.size());
        for (int index = 0; index < limit; index++) {
            FallbackScore item = scored.get(index);
            MemoryRecord record = decode(query.scope(), item.document);
            results.add(new MemorySearchResult(record, item.score, index + 1, provenance(record)));
        }
        return new MemoryPage<>(results, null);
    }

    private MemoryProvenance provenance(MemoryRecord record) {
        String citation = "cosmos://"
                + options.storage().container().databaseId()
                + "/"
                + options.storage().container().containerId()
                + "/"
                + CosmosMemorySdkSupport.itemId(options, record);
        return new MemoryProvenance("azure-cosmos", record.key().memoryId(), citation);
    }

    CosmosQueryRequestOptions queryOptions(MemoryScope scope) {
        return new CosmosQueryRequestOptions()
                .setPartitionKey(partitionKey(scope))
                .setMaxDegreeOfParallelism(1)
                .setMaxBufferedItemCount(options.pageSize());
    }

    private PartitionKey partitionKey(MemoryScope scope) {
        return new PartitionKey(partitionKeyValue(scope));
    }

    private String partitionKeyValue(MemoryScope scope) {
        return CosmosMemorySdkSupport.partitionKey(options, scope);
    }

    private <T> CompletionStage<T> conflictMapped(
            CompletionStage<T> operation, String memoryId, long expectedRevision, Long actual) {
        CompletableFuture<T> result = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else if (CosmosMemorySdkSupport.hasStatus(failure, 409)
                    || CosmosMemorySdkSupport.hasStatus(failure, 412)) {
                result.completeExceptionally(CosmosMemorySdkSupport.conflict(memoryId, expectedRevision, actual));
            } else {
                result.completeExceptionally(CosmosMemorySdkSupport.mapFailure(failure));
            }
        });
        return result.minimalCompletionStage();
    }

    private <T> CompletionStage<T> afterInitialization(java.util.function.Supplier<CompletionStage<T>> operation) {
        if (closed.get()) {
            return CompletableFuture.failedStage(new CosmosStorageException(
                    "Cosmos memory store is closed.", null, CosmosStorageException.Kind.CLOSED, null));
        }
        return initialization.thenCompose(ignored -> operation.get());
    }

    private List<String> terms(String text) {
        ArrayList<String> terms = new ArrayList<>();
        Matcher matcher = TERM_PATTERN.matcher(text);
        while (matcher.find() && terms.size() < options.maxQueryTerms()) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (!terms.contains(term)) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            throw new ValidationException("Memory full-text query contains no searchable terms.");
        }
        return List.copyOf(terms);
    }

    private boolean isIdempotentRetry(CosmosMemoryDocument current, String digest, long expectedRevision) {
        return current != null
                && digest.equals(current.payloadDigest)
                && ((expectedRevision == CREATE_ONLY && current.revision == 1)
                        || (expectedRevision > 0
                                && expectedRevision < Long.MAX_VALUE
                                && current.revision == expectedRevision + 1));
    }

    private static double serverScore(Double raw) {
        if (raw == null || !Double.isFinite(raw)) {
            throw incompatible("Cosmos memory search returned a non-finite score.", null);
        }
        return raw;
    }

    private static double lexicalScore(String content, List<String> terms) {
        String lower = content.toLowerCase(Locale.ROOT);
        long matches = terms.stream().filter(lower::contains).count();
        return matches / (double) terms.size();
    }

    private double vectorScore(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) {
            throw incompatible("Stored Cosmos memory vector dimension is incompatible.", null);
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        double squaredDistance = 0;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
            double difference = a - b;
            squaredDistance += difference * difference;
        }
        return switch (options.vector().distance()) {
            case COSINE ->
                leftNorm == 0 || rightNorm == 0 ? 0 : Math.max(0, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
            case DOT_PRODUCT -> 1.0 / (1.0 + Math.exp(-dot));
            case EUCLIDEAN -> 1.0 / (1.0 + Math.sqrt(squaredDistance));
        };
    }

    private static long nextRevision(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw incompatible("Cosmos memory revision is exhausted.", null);
        }
        return revision + 1;
    }

    private static boolean requiresFullText(MemorySearchMode mode) {
        return mode == MemorySearchMode.FULL_TEXT || mode == MemorySearchMode.HYBRID;
    }

    private ValidationException validateUpsert(
            MemoryRecord record, long expectedRevision, RunCancellation cancellation) {
        if (record == null) {
            return new ValidationException("record must not be null.");
        }
        if (cancellation == null) {
            return new ValidationException("cancellation must not be null.");
        }
        try {
            CosmosMemorySdkSupport.validateScope(options, record.key().scope());
        } catch (ValidationException exception) {
            return exception;
        }
        if (expectedRevision != CREATE_ONLY && expectedRevision <= 0) {
            return new ValidationException("expectedRevision must be -1 for create or greater than zero.");
        }
        if (record.embedding() != null
                && record.embedding().dimensions() != options.vector().dimensions()) {
            return new ValidationException(
                    "Memory embedding dimensions do not match the configured Cosmos vector policy.");
        }
        try {
            validateRecordSize(record);
        } catch (ValidationException exception) {
            return exception;
        }
        return null;
    }

    private ValidationException validateKey(MemoryKey key, RunCancellation cancellation) {
        if (key == null) {
            return new ValidationException("key must not be null.");
        }
        if (cancellation == null) {
            return new ValidationException("cancellation must not be null.");
        }
        try {
            CosmosMemorySdkSupport.validateScope(options, key.scope());
        } catch (ValidationException exception) {
            return exception;
        }
        return null;
    }

    private ValidationException validateDelete(MemoryKey key, long expectedRevision, RunCancellation cancellation) {
        ValidationException keyFailure = validateKey(key, cancellation);
        if (keyFailure != null) {
            return keyFailure;
        }
        return expectedRevision <= 0
                ? new ValidationException("delete expectedRevision must be greater than zero.")
                : null;
    }

    private ValidationException validateList(MemoryListRequest request, RunCancellation cancellation) {
        if (request == null) {
            return new ValidationException("request must not be null.");
        }
        if (cancellation == null) {
            return new ValidationException("cancellation must not be null.");
        }
        try {
            CosmosMemorySdkSupport.validateScope(options, request.scope());
            validateFilter(request.filter());
        } catch (ValidationException exception) {
            return exception;
        }
        return request.filter().equals().size() > options.maxFilterTerms()
                ? new ValidationException("Memory filter exceeds configured maxFilterTerms.")
                : null;
    }

    private ValidationException validateSearch(MemoryQuery query, RunCancellation cancellation) {
        if (query == null) {
            return new ValidationException("query must not be null.");
        }
        if (cancellation == null) {
            return new ValidationException("cancellation must not be null.");
        }
        try {
            CosmosMemorySdkSupport.validateScope(options, query.scope());
            validateFilter(query.filter());
        } catch (ValidationException exception) {
            return exception;
        }
        if (query.text() != null && utf8Bytes(query.text()) > CosmosMemoryOptions.MAX_STRING_BYTES) {
            return new ValidationException("Memory query text exceeds the maximum UTF-8 bytes.");
        }
        if (query.filter().equals().size() > options.maxFilterTerms()) {
            return new ValidationException("Memory filter exceeds configured maxFilterTerms.");
        }
        if (query.cursor() != null) {
            return new ValidationException("Cosmos memory search does not support continuation cursors.");
        }
        if (query.embedding() != null
                && query.embedding().dimensions() != options.vector().dimensions()) {
            return new ValidationException(
                    "Query embedding dimensions do not match the configured Cosmos vector policy.");
        }
        return null;
    }

    private void validateRecordSize(MemoryRecord record) {
        if (utf8Bytes(record.content()) > CosmosMemoryOptions.MAX_STRING_BYTES) {
            throw new ValidationException("Memory content exceeds the maximum UTF-8 bytes.");
        }
        Object metadata = MemoryStateValueMapper.toObject(
                StateValue.object(record.metadata().values()));
        long estimatedBytes = 2048L
                + utf8Bytes(record.key().memoryId())
                + utf8Bytes(record.content())
                + utf8Bytes(record.createdAt().toString())
                + utf8Bytes(record.updatedAt().toString())
                + utf8Bytes(metadata.toString())
                + (record.embedding() == null ? 0L : record.embedding().dimensions() * 24L);
        if (estimatedBytes > options.storage().maxDocumentBytes()) {
            throw new ValidationException("Memory record exceeds configured maxDocumentBytes.");
        }
    }

    private void validateStoredSize(CosmosMemoryDocument document) {
        if (utf8Bytes(document.content) > CosmosMemoryOptions.MAX_STRING_BYTES) {
            throw incompatible("Stored Cosmos memory content exceeds the maximum string bytes.", null);
        }
        Object metadata = MemoryStateValueMapper.toObject(MemoryStateValueMapper.fromObject(document.metadata));
        long estimatedBytes = 2048L
                + utf8Bytes(document.memoryId)
                + utf8Bytes(document.content)
                + utf8Bytes(document.createdAt)
                + utf8Bytes(document.updatedAt)
                + utf8Bytes(metadata.toString())
                + (document.vector == null ? 0L : document.vector.size() * 24L);
        if (estimatedBytes > options.storage().maxDocumentBytes()) {
            throw incompatible("Stored Cosmos memory exceeds configured maxDocumentBytes.", null);
        }
    }

    private void validateMetadataPairs(CosmosMemoryDocument document) {
        if (document.metadataPairs.size() != document.metadata.size()) {
            throw incompatible("Stored Cosmos memory metadata filter pairs are inconsistent.", null);
        }
        LinkedHashMap<String, StateValue> pairs = new LinkedHashMap<>();
        for (Map<String, Object> pair : document.metadataPairs) {
            if (pair == null
                    || !pair.keySet().equals(java.util.Set.of("key", "value"))
                    || !(pair.get("key") instanceof String key)
                    || key.isBlank()
                    || pairs.put(key, MemoryStateValueMapper.fromObject(pair.get("value"))) != null) {
                throw incompatible("Stored Cosmos memory metadata filter pairs are malformed.", null);
            }
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        document.metadata.forEach((key, value) -> metadata.put(key, MemoryStateValueMapper.fromObject(value)));
        if (!pairs.equals(metadata)) {
            throw incompatible("Stored Cosmos memory metadata filter pairs do not match metadata.", null);
        }
    }

    private static void validateFilter(MemoryFilter filter) {
        MemoryStateValueMapper.toObject(StateValue.object(filter.equals()));
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static CosmosStorageException incompatible(String message, Throwable cause) {
        return new CosmosStorageException(message, cause, CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE, null);
    }

    record QueryPlan(SqlQuerySpec query, boolean serverScore) {}

    record SearchPlan(SqlQuerySpec query, boolean serverScore) {}

    private record FallbackScore(CosmosMemoryDocument document, double score) {}
}
