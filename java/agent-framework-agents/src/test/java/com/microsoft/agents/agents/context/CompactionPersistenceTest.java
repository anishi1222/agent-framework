// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.AgentSessionStateBag;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.agents.InMemoryHistoryProvider;
import com.microsoft.agents.agents.InMemorySessionStore;
import com.microsoft.agents.agents.SessionKey;
import com.microsoft.agents.agents.SessionStore;
import com.microsoft.agents.agents.SessionStoreDurability;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompactionPersistenceTest {
    @Test
    void historyDecoratorCompactsRequestWithoutReplacingSessionHistory() {
        // Arrange
        AgentSession session = new AgentSession("session-request-only");
        ContextProviderRequest request = request(session);
        InMemoryHistoryProvider history = new InMemoryHistoryProvider();
        List<Message> source = history();
        history.appendMessagesAsync(request, source).toCompletableFuture().join();
        CompactingHistoryProvider provider =
                new CompactingHistoryProvider("compact", history, new SlidingWindowCompactionStrategy(1));

        // Act
        ContextContribution contribution =
                provider.provideAsync(request).toCompletableFuture().join();

        // Assert
        assertThat(contribution.messages()).extracting(Message::messageId).containsExactly("s", "u2", "a2");
        assertThat(contribution.metadata()).containsKey("agentFramework.compaction.compact");
        assertThat(session.messages()).containsExactlyElementsOf(source);
    }

    @Test
    void historyDecoratorPreservesDelegateContributionFields() {
        // Arrange
        AgentSession session = new AgentSession("session-contribution");
        ContextProviderRequest request = request(session);
        HistoryProvider delegate = new HistoryProvider() {
            @Override
            public String id() {
                return "delegate";
            }

            @Override
            public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest ignored) {
                return CompletableFuture.completedFuture(history());
            }

            @Override
            public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest ignored, List<Message> messages) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest ignored) {
                return CompletableFuture.completedFuture(new ContextContribution(
                        List.of("preserved instruction"),
                        history(),
                        Map.of("delegate", com.microsoft.agents.core.StateValue.string("metadata")),
                        List.of()));
            }
        };
        CompactingHistoryProvider provider =
                new CompactingHistoryProvider("compact", delegate, new SlidingWindowCompactionStrategy(1));

        // Act
        ContextContribution contribution =
                provider.provideAsync(request).toCompletableFuture().join();

        // Assert
        assertThat(contribution.instructions()).containsExactly("preserved instruction");
        assertThat(contribution.metadata()).containsKey("delegate");
        assertThat(contribution.messages()).extracting(Message::messageId).containsExactly("s", "u2", "a2");
    }

    @Test
    void historyDecoratorForwardsDelegateCompletionHook() {
        // Arrange
        AtomicInteger completions = new AtomicInteger();
        HistoryProvider delegate = new HistoryProvider() {
            @Override
            public String id() {
                return "delegate";
            }

            @Override
            public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages) {
                throw new AssertionError("delegate completion override must be used");
            }

            @Override
            public CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
                completions.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        CompactingHistoryProvider provider =
                new CompactingHistoryProvider("compact", delegate, new SlidingWindowCompactionStrategy(1));
        ContextProviderRequest request = request(new AgentSession("completion"));
        ContextProviderCompletion completion = new ContextProviderCompletion(
                request,
                List.of(Message.text(Role.USER, "input")),
                AgentResponse.<Void>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "output")))
                        .build(),
                null);

        // Act
        provider.completedAsync(completion).toCompletableFuture().join();

        // Assert
        assertThat(completions).hasValue(1);
    }

    @Test
    void sharedDecoratorKeepsConcurrentSessionsIsolated() {
        // Arrange
        InMemoryHistoryProvider history = new InMemoryHistoryProvider();
        CompactingHistoryProvider provider =
                new CompactingHistoryProvider("compact", history, new SlidingWindowCompactionStrategy(1));
        AgentSession firstSession = new AgentSession("first-session");
        AgentSession secondSession = new AgentSession("second-session");
        ContextProviderRequest firstRequest = request(firstSession);
        ContextProviderRequest secondRequest = request(secondSession);
        List<Message> firstHistory = prefixedHistory("first");
        List<Message> secondHistory = prefixedHistory("second");
        history.appendMessagesAsync(firstRequest, firstHistory)
                .toCompletableFuture()
                .join();
        history.appendMessagesAsync(secondRequest, secondHistory)
                .toCompletableFuture()
                .join();

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<ContextContribution> first = CompletableFuture.supplyAsync(
                    () -> provider.provideAsync(firstRequest)
                            .toCompletableFuture()
                            .join(),
                    executor);
            CompletableFuture<ContextContribution> second = CompletableFuture.supplyAsync(
                    () -> provider.provideAsync(secondRequest)
                            .toCompletableFuture()
                            .join(),
                    executor);

            // Assert
            assertThat(first.join().messages())
                    .extracting(Message::messageId)
                    .containsExactly("first-s", "first-u2", "first-a2");
            assertThat(second.join().messages())
                    .extracting(Message::messageId)
                    .containsExactly("second-s", "second-u2", "second-a2");
        }
        assertThat(firstSession.messages()).containsExactlyElementsOf(firstHistory);
        assertThat(secondSession.messages()).containsExactlyElementsOf(secondHistory);
    }

    @Test
    void explicitPersistedReplacementUsesLoadedRevision() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("persist-success");
        AgentSessionSnapshot source = new AgentSessionSnapshot(key.value(), history(), AgentSessionStateBag.empty());
        VersionedSnapshot<AgentSessionSnapshot> created = store.saveAsync(key, source, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        // Act
        PersistedCompactionResult result = PersistedHistoryCompactor.compactAsync(
                        store, key, new SlidingWindowCompactionStrategy(1))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.replaced()).isTrue();
        assertThat(result.storedSnapshot().revision()).isGreaterThan(created.revision());
        assertThat(result.storedSnapshot().snapshot().messages())
                .extracting(Message::messageId)
                .containsExactly("s", "u2", "a2");
    }

    @Test
    void persistedConflictPropagatesAndOriginalSnapshotRemains() {
        // Arrange
        InMemorySessionStore backing = new InMemorySessionStore();
        SessionKey key = new SessionKey("persist-conflict");
        AgentSessionSnapshot source = new AgentSessionSnapshot(key.value(), history(), AgentSessionStateBag.empty());
        backing.saveAsync(key, source, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        SessionStore conflictStore = new ConflictOnSaveStore(backing);

        // Act / Assert
        assertThatThrownBy(() -> PersistedHistoryCompactor.compactAsync(
                                conflictStore, key, new SlidingWindowCompactionStrategy(1))
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(StorageConflictException.class);
        AgentSessionSnapshot stored = backing.loadAsync(key)
                .toCompletableFuture()
                .join()
                .orElseThrow()
                .snapshot();
        assertThat(stored.messages()).containsExactlyElementsOf(source.messages());
    }

    @Test
    void failedSummaryNeverWritesPersistedHistory() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey key = new SessionKey("summary-failure");
        AgentSessionSnapshot source = new AgentSessionSnapshot(key.value(), history(), AgentSessionStateBag.empty());
        VersionedSnapshot<AgentSessionSnapshot> created = store.saveAsync(key, source, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        com.microsoft.agents.agents.ChatClient failing = new com.microsoft.agents.agents.ChatClient() {
            @Override
            public CompletionStage<com.microsoft.agents.core.ChatResponse> completeAsync(
                    com.microsoft.agents.agents.ChatClientRequest request,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                return CompletableFuture.failedFuture(new IllegalStateException("failed"));
            }

            @Override
            public java.util.concurrent.Flow.Publisher<com.microsoft.agents.core.ChatResponseUpdate> completeStreaming(
                    com.microsoft.agents.agents.ChatClientRequest request,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                throw new UnsupportedOperationException();
            }
        };

        // Act / Assert
        assertThatThrownBy(() -> PersistedHistoryCompactor.compactAsync(
                                store, key, new SummarizationCompactionStrategy(failing, 3, 1, 1_000))
                        .toCompletableFuture()
                        .join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        VersionedSnapshot<AgentSessionSnapshot> stored =
                store.loadAsync(key).toCompletableFuture().join().orElseThrow();
        assertThat(stored.revision()).isEqualTo(created.revision());
        assertThat(stored.snapshot().messages()).containsExactlyElementsOf(source.messages());
    }

    @Test
    void oversizedSummaryInputReturnsAuditWithoutCallingSummarizerOrSaving() {
        // Arrange
        InMemorySessionStore backing = new InMemorySessionStore();
        SessionKey key = new SessionKey("summary-overflow");
        List<Message> messages = List.of(
                message(Role.USER, "x".repeat(10_000), "huge"),
                message(Role.ASSISTANT, "old answer", "old"),
                message(Role.USER, "recent", "recent"));
        AgentSessionSnapshot source = new AgentSessionSnapshot(key.value(), messages, AgentSessionStateBag.empty());
        VersionedSnapshot<AgentSessionSnapshot> created = backing.saveAsync(key, source, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        CountingSaveStore store = new CountingSaveStore(backing);
        AtomicInteger summarizerCalls = new AtomicInteger();
        com.microsoft.agents.agents.ChatClient summarizer = new com.microsoft.agents.agents.ChatClient() {
            @Override
            public CompletionStage<com.microsoft.agents.core.ChatResponse> completeAsync(
                    com.microsoft.agents.agents.ChatClientRequest request,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                summarizerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(com.microsoft.agents.core.ChatResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "unused")))
                        .build());
            }

            @Override
            public java.util.concurrent.Flow.Publisher<com.microsoft.agents.core.ChatResponseUpdate> completeStreaming(
                    com.microsoft.agents.agents.ChatClientRequest request,
                    com.microsoft.agents.core.RunCancellation cancellation) {
                throw new UnsupportedOperationException();
            }
        };

        // Act
        PersistedCompactionResult result = PersistedHistoryCompactor.compactAsync(
                        store, key, new SummarizationCompactionStrategy(summarizer, 2, 1, 10))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.replaced()).isFalse();
        assertThat(result.compaction().messages()).containsExactlyElementsOf(messages);
        assertThat(result.compaction().audit().limitStatus())
                .isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(result.storedSnapshot().revision()).isEqualTo(created.revision());
        assertThat(store.saveCalls).hasValue(0);
        assertThat(summarizerCalls).hasValue(0);
        VersionedSnapshot<AgentSessionSnapshot> stored =
                backing.loadAsync(key).toCompletableFuture().join().orElseThrow();
        assertThat(stored.revision()).isEqualTo(created.revision());
        assertThat(stored.snapshot().messages()).containsExactlyElementsOf(messages);
    }

    @Test
    void requiredContentOverflowNeverPersistsPartialProjection() {
        // Arrange
        InMemorySessionStore backing = new InMemorySessionStore();
        SessionKey key = new SessionKey("required-overflow");
        Message pending = Message.builder(Role.ASSISTANT)
                .contents(List.of(new com.microsoft.agents.core.FunctionCallContent(
                        "pending", "approval", com.microsoft.agents.core.StateValue.nullValue())))
                .messageId("pending")
                .metadata(Map.of("approval.state", com.microsoft.agents.core.StateValue.string("pending")))
                .build();
        List<Message> messages =
                List.of(message(Role.USER, "old", "old"), pending, message(Role.USER, "recent", "recent"));
        AgentSessionSnapshot source = new AgentSessionSnapshot(key.value(), messages, AgentSessionStateBag.empty());
        VersionedSnapshot<AgentSessionSnapshot> created = backing.saveAsync(key, source, SessionStore.CREATE_ONLY)
                .toCompletableFuture()
                .join();
        CountingSaveStore store = new CountingSaveStore(backing);

        // Act
        PersistedCompactionResult result = PersistedHistoryCompactor.compactAsync(
                        store, key, new TokenBudgetCompactionStrategy(1, 0), message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.replaced()).isFalse();
        assertThat(result.compaction().messages()).containsExactlyElementsOf(messages);
        assertThat(result.compaction().audit().changed()).isFalse();
        assertThat(result.compaction().audit().limitStatus())
                .isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(result.storedSnapshot().revision()).isEqualTo(created.revision());
        assertThat(store.saveCalls).hasValue(0);
    }

    private static ContextProviderRequest request(AgentSession session) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AgentRunContext runContext = new AgentRunContext(
                "run",
                new AgentMetadata("agent", "Agent", "test"),
                Instant.EPOCH,
                List.of(),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(), List.of(), Map.of(), List.of());
    }

    private static List<Message> history() {
        return List.of(
                message(Role.SYSTEM, "system", "s"),
                message(Role.USER, "first", "u1"),
                message(Role.ASSISTANT, "answer one", "a1"),
                message(Role.USER, "second", "u2"),
                message(Role.ASSISTANT, "answer two", "a2"));
    }

    private static List<Message> prefixedHistory(String prefix) {
        return List.of(
                message(Role.SYSTEM, "system", prefix + "-s"),
                message(Role.USER, "first", prefix + "-u1"),
                message(Role.ASSISTANT, "answer one", prefix + "-a1"),
                message(Role.USER, "second", prefix + "-u2"),
                message(Role.ASSISTANT, "answer two", prefix + "-a2"));
    }

    private static Message message(Role role, String text, String id) {
        return Message.builder(role)
                .contents(List.of(new com.microsoft.agents.core.TextContent(text)))
                .messageId(id)
                .build();
    }

    private record ConflictOnSaveStore(InMemorySessionStore delegate) implements SessionStore {
        @Override
        public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
            return delegate.loadAsync(key);
        }

        @Override
        public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
                SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
            return CompletableFuture.failedFuture(new StorageConflictException("simulated conflict"));
        }

        @Override
        public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
            return delegate.deleteAsync(key, expectedRevision);
        }

        @Override
        public SessionStoreDurability durability() {
            return delegate.durability();
        }
    }

    private static final class CountingSaveStore implements SessionStore {
        private final InMemorySessionStore delegate;

        private final AtomicInteger saveCalls = new AtomicInteger();

        private CountingSaveStore(InMemorySessionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
            return delegate.loadAsync(key);
        }

        @Override
        public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
                SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
            saveCalls.incrementAndGet();
            return delegate.saveAsync(key, snapshot, expectedRevision);
        }

        @Override
        public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
            return delegate.deleteAsync(key, expectedRevision);
        }

        @Override
        public SessionStoreDurability durability() {
            return delegate.durability();
        }
    }
}
