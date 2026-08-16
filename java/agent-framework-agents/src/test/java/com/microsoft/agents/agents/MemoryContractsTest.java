// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.agents.memory.MemoryContextOptions;
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
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class MemoryContractsTest {
    private static final MemoryScope SCOPE = new MemoryScope("tenant", "user");

    @Test
    void models_shouldCopyMetadataFiltersAndVectorsAndRejectInvalidDimensions() {
        // Arrange
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.put("category", StateValue.string("preference"));
        LinkedHashMap<String, StateValue> filter = new LinkedHashMap<>(metadata);
        java.util.ArrayList<Double> vector = new java.util.ArrayList<>(List.of(1.0, 2.0));

        // Act
        MemoryMetadata copiedMetadata = new MemoryMetadata(metadata);
        MemoryFilter copiedFilter = new MemoryFilter(filter);
        EmbeddingVector copiedVector = new EmbeddingVector(vector);
        metadata.clear();
        filter.clear();
        vector.set(0, 99.0);

        // Assert
        assertThat(copiedMetadata.values()).containsKey("category");
        assertThat(copiedFilter.equals()).containsKey("category");
        assertThat(copiedVector.values()).containsExactly(1.0, 2.0);
        assertThatThrownBy(() -> new EmbeddingVector(List.of(Double.NaN))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EmbeddingVector(List.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MemoryFilter(Map.of("nested", StateValue.object(Map.of()))))
                .isInstanceOf(ValidationException.class);
        MemoryRecord record = record();
        MemoryProvenance provenance = new MemoryProvenance("test", "memory-1", "memory://memory-1");
        MemorySearchResult scored = new MemorySearchResult(record, 0.9, 1, provenance);
        MemorySearchResult ranked = MemorySearchResult.ranked(record, 1, provenance);
        MemoryQuery paged =
                new MemoryQuery(SCOPE, "query", null, MemoryFilter.none(), MemorySearchMode.FULL_TEXT, 1, "cursor");
        MemoryQuery singlePage =
                new MemoryQuery(SCOPE, "query", null, MemoryFilter.none(), MemorySearchMode.FULL_TEXT, 1);

        assertThat(scored.score()).isEqualTo(0.9);
        assertThat(scored.hasScore()).isTrue();
        assertThat(scored.optionalScore()).hasValue(0.9);
        assertThat(Double.isNaN(ranked.score())).isTrue();
        assertThat(ranked.hasScore()).isFalse();
        assertThat(ranked.optionalScore()).isEmpty();
        assertThat(paged.cursor()).isEqualTo("cursor");
        assertThat(singlePage.cursor()).isNull();
        assertThatThrownBy(() -> new MemorySearchResult(record, Double.POSITIVE_INFINITY, 1, provenance))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MemorySearchResult(record, Double.NEGATIVE_INFINITY, 1, provenance))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void contextProvider_shouldPersistRetrievedReferenceOnlyWhenExplicitlyConfigured() {
        // Arrange
        MemoryRecord record = record();
        MemoryStore store = fixedStore(
                new MemorySearchResult(record, 0.9, 1, new MemoryProvenance("test", "memory-1", "memory://memory-1")));
        AgentSession defaultSession = new AgentSession("default-session");
        AgentSession persistentSession = new AgentSession("persistent-session");
        MemoryContextProvider safeDefault = new MemoryContextProvider(
                "memory-default", store, SCOPE, null, new MemoryContextOptions(1, 1000, 100, 200, false));
        MemoryContextProvider persistent = new MemoryContextProvider(
                "memory-persistent", store, SCOPE, null, new MemoryContextOptions(1, 1000, 100, 200, true));
        ContextProviderRequest defaultRequest = request(defaultSession, "run-default");
        ContextProviderRequest persistentRequest = request(persistentSession, "run-persistent");

        // Act
        safeDefault.provideAsync(defaultRequest).toCompletableFuture().join();
        persistent.provideAsync(persistentRequest).toCompletableFuture().join();
        safeDefault
                .completedAsync(success(defaultRequest))
                .toCompletableFuture()
                .join();
        persistent
                .completedAsync(success(persistentRequest))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(defaultSession.messages()).isEmpty();
        assertThat(persistentSession.messages()).singleElement().satisfies(message -> {
            assertThat(message.role()).isEqualTo(Role.USER);
            assertThat(message.text()).contains("untrusted reference data");
            assertThat(message.metadata()).containsKey("memoryProvenance");
        });
    }

    private static ContextProviderCompletion success(ContextProviderRequest request) {
        return new ContextProviderCompletion(
                request,
                request.runContext().inputMessages(),
                AgentResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "response")))
                        .build(),
                null);
    }

    private static ContextProviderRequest request(AgentSession session, String runId) {
        Message input = Message.text(Role.USER, "remember");
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

    private static MemoryRecord record() {
        return MemoryRecord.create(
                new MemoryKey(SCOPE, "memory-1"),
                "Prefers concise answers.",
                MemoryMetadata.empty(),
                null,
                Instant.parse("2026-08-12T00:00:00Z"));
    }

    private static MemoryStore fixedStore(MemorySearchResult result) {
        return new MemoryStore() {
            @Override
            public CompletionStage<VersionedSnapshot<MemoryRecord>> putAsync(
                    MemoryRecord record, RunCancellation cancellation) {
                return CompletableFuture.completedStage(new VersionedSnapshot<>(record, 1));
            }

            @Override
            public CompletionStage<VersionedSnapshot<MemoryRecord>> upsertAsync(
                    MemoryRecord record, long expectedRevision, RunCancellation cancellation) {
                return CompletableFuture.completedStage(new VersionedSnapshot<>(record, expectedRevision + 1));
            }

            @Override
            public CompletionStage<Optional<VersionedSnapshot<MemoryRecord>>> getAsync(
                    MemoryKey key, RunCancellation cancellation) {
                return CompletableFuture.completedStage(Optional.empty());
            }

            @Override
            public CompletionStage<Void> deleteAsync(
                    MemoryKey key, long expectedRevision, RunCancellation cancellation) {
                return CompletableFuture.completedStage(null);
            }

            @Override
            public CompletionStage<MemoryPage<VersionedSnapshot<MemoryRecord>>> listAsync(
                    MemoryListRequest request, RunCancellation cancellation) {
                return CompletableFuture.completedStage(new MemoryPage<>(List.of(), null));
            }

            @Override
            public CompletionStage<MemoryPage<MemorySearchResult>> searchAsync(
                    MemoryQuery query, RunCancellation cancellation) {
                assertThat(query.mode()).isEqualTo(MemorySearchMode.FULL_TEXT);
                return CompletableFuture.completedStage(new MemoryPage<>(List.of(result), null));
            }
        };
    }
}
