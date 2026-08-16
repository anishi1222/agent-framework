// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Mem0RestClientTest {
    private static final String API_KEY = "secret-api-key-value";

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedResponses")
    void searchAsync_shouldRejectMalformedOrLimitBreakingJson(String name, String body, Mem0LimitOptions limits)
            throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server, limits, 0, Duration.ofSeconds(2))) {
            server.enqueueJson(200, body);

            // Act
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "q", new DefaultRunCancellation()));

            // Assert
            assertThat(name).isNotBlank();
            assertThat(failure)
                    .isInstanceOf(Mem0StorageException.class)
                    .extracting("kind")
                    .isEqualTo(Mem0StorageException.Kind.DATA_CONTRACT);
        }
    }

    @Test
    void searchAsync_shouldRejectWrongSuccessContentType() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueue(new Mem0TestServer.Response(200, "text/plain", "{\"results\":[]}", Map.of(), Duration.ZERO));

            // Act
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.DATA_CONTRACT);
        }
    }

    @Test
    void searchAsync_shouldRejectOversizeResponseBeforeParsing() throws Exception {
        // Arrange
        Mem0LimitOptions limits =
                Mem0LimitOptions.builder().maxResponseBytes(64).build();
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server, limits, 0, Duration.ofSeconds(2))) {
            server.enqueueJson(
                    200, "{\"results\":[{\"id\":\"one\",\"memory\":\"" + "x".repeat(128) + "\",\"score\":0.9}]}");

            // Act
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.DATA_CONTRACT);
        }
    }

    @Test
    void searchAsync_shouldAcceptDocumentedResultsWithoutScore() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueueJson(200, "{\"results\":[{\"id\":\"one\",\"memory\":\"fact\"}]}");

            // Act
            List<Mem0Memory> memories = provider.searchAsync(
                            Mem0Scope.forUser("user"), "query", new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(memories).singleElement().satisfies(memory -> {
                assertThat(memory.id()).isEqualTo("one");
                assertThat(memory.score()).isNull();
                assertThat(memory.rank()).isEqualTo(1);
            });
        }
    }

    @Test
    void searchAsync_shouldNotFollowRedirectsOrLeakAuthorizationCrossOrigin() throws Exception {
        // Arrange
        try (Mem0TestServer target = new Mem0TestServer();
                Mem0TestServer source = new Mem0TestServer();
                Mem0ContextProvider provider = provider(source)) {
            source.enqueue(new Mem0TestServer.Response(
                    302,
                    "application/json",
                    "{\"message\":\"redirect\"}",
                    Map.of("Location", target.endpoint().uri() + "v3/memories/search/"),
                    Duration.ZERO));

            // Act
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.SERVICE);
            assertThat(((Mem0StorageException) failure).statusCode()).isEqualTo(302);
            assertThat(target.requests()).isEmpty();
            assertThat(source.requests())
                    .singleElement()
                    .satisfies(request ->
                            assertThat(request.header("authorization")).isEqualTo("Token " + API_KEY));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void searchAsync_shouldRetryOnlyDocumentedIdempotentStatuses(int status) throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider =
                        provider(server, Mem0LimitOptions.defaults(), 1, Duration.ofSeconds(2))) {
            server.enqueueJson(status, "{\"error\":\"retry\"}", Map.of("Retry-After", "0"));
            server.enqueueJson(200, "{\"results\":[]}");

            // Act
            List<Mem0Memory> memories = provider.searchAsync(
                            Mem0Scope.forUser("user"), "query", new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(memories).isEmpty();
            assertThat(server.requests()).hasSize(2);
        }
    }

    @Test
    void eventAndScopedClear_shouldUseTheSameBoundedIdempotentRetryPolicy() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider =
                        provider(server, Mem0LimitOptions.defaults(), 1, Duration.ofSeconds(2))) {
            server.enqueueJson(200, "{\"event_id\":\"retry-event\",\"status\":\"PENDING\"}");
            server.enqueueJson(503, "{\"error\":\"retry\"}", Map.of("Retry-After", "0"));
            server.enqueueJson(200, "{\"id\":\"retry-event\",\"status\":\"SUCCEEDED\",\"results\":[]}");
            server.enqueueJson(429, "{\"error\":\"retry\"}", Map.of("Retry-After", "0"));
            server.enqueueJson(200, "{}");
            ContextProviderRequest request = request("run-event-retry");
            ContextProviderCompletion completion = new ContextProviderCompletion(
                    request,
                    request.runContext().inputMessages(),
                    AgentResponse.builder().messages(List.of()).build(),
                    null);

            // Act
            provider.completedAsync(completion).toCompletableFuture().join();
            provider.clearAsync(Mem0Scope.forUser("user"), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(server.requests())
                    .extracting(Mem0TestServer.RecordedRequest::path)
                    .containsExactly(
                            "/v3/memories/add/",
                            "/v1/event/retry-event/",
                            "/v1/event/retry-event/",
                            "/v1/memories/",
                            "/v1/memories/");
        }
    }

    @Test
    void completedAsync_shouldNeverBlindRetrySideEffectingAdd() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider =
                        provider(server, Mem0LimitOptions.defaults(), 3, Duration.ofSeconds(2))) {
            server.enqueueJson(500, "{\"error\":\"accepted but response lost\"}");
            ContextProviderRequest request = request("run-add-no-retry");
            ContextProviderCompletion completion = new ContextProviderCompletion(
                    request,
                    request.runContext().inputMessages(),
                    AgentResponse.builder()
                            .messages(List.of(Message.text(Role.ASSISTANT, "answer")))
                            .build(),
                    null);

            // Act
            Throwable failure = failure(provider.completedAsync(completion));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.SERVICE);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void searchAsync_shouldHonorTotalOperationDeadline() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider =
                        provider(server, Mem0LimitOptions.defaults(), 0, Duration.ofMillis(50))) {
            server.enqueue(new Mem0TestServer.Response(
                    200, "application/json", "{\"results\":[]}", Map.of(), Duration.ofMillis(500)));

            // Act
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.TIMEOUT);
        }
    }

    @Test
    void searchAsync_shouldCancelTheJdkRequestFuture() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueue(new Mem0TestServer.Response(
                    200, "application/json", "{\"results\":[]}", Map.of(), Duration.ofSeconds(2)));
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            CompletionStage<List<Mem0Memory>> search =
                    provider.searchAsync(Mem0Scope.forUser("user"), "query", cancellation);
            server.awaitRequestCount(1);

            // Act
            cancellation.cancel();
            Throwable failure = failure(search);

            // Assert
            assertThat(failure).isInstanceOf(RunCancelledException.class);
        }
    }

    @Test
    void completedAsync_shouldNotStartAddWhenCancellationWinsRequestInitiationRace() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueueJson(200, "{\"status\":\"SUCCEEDED\",\"results\":[]}");
            RacingCancellation cancellation = new RacingCancellation();
            ContextProviderRequest request = request("run-cancel-before-send", cancellation);
            ContextProviderCompletion completion = new ContextProviderCompletion(
                    request,
                    request.runContext().inputMessages(),
                    AgentResponse.builder()
                            .messages(List.of(Message.text(Role.ASSISTANT, "answer")))
                            .build(),
                    null);

            // Act
            Throwable failure = failure(provider.completedAsync(completion));
            Thread.sleep(100);

            // Assert
            assertThat(failure).isInstanceOf(RunCancelledException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void searchAsync_shouldEnforceConcurrentRequestSemaphore() throws Exception {
        // Arrange
        Mem0LimitOptions limits =
                Mem0LimitOptions.builder().maxConcurrentRequests(1).build();
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server, limits, 0, Duration.ofSeconds(3))) {
            server.enqueue(new Mem0TestServer.Response(
                    200, "application/json", "{\"results\":[]}", Map.of(), Duration.ofSeconds(1)));
            DefaultRunCancellation firstCancellation = new DefaultRunCancellation();
            CompletionStage<List<Mem0Memory>> first =
                    provider.searchAsync(Mem0Scope.forUser("one"), "query", firstCancellation);
            server.awaitRequestCount(1);

            // Act
            Throwable secondFailure =
                    failure(provider.searchAsync(Mem0Scope.forUser("two"), "query", new DefaultRunCancellation()));
            firstCancellation.cancel();
            failure(first);

            // Assert
            assertKind(secondFailure, Mem0StorageException.Kind.CONCURRENCY_LIMIT);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void authenticationFailure_shouldNotExposeKeyScopeQueryOrResponseBody() throws Exception {
        // Arrange
        String scopeSecret = "sensitive-user";
        String querySecret = "sensitive-query";
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueueJson(401, "{\"error\":\"" + API_KEY + " " + scopeSecret + " " + querySecret + "\"}");

            // Act
            Throwable failure = failure(
                    provider.searchAsync(Mem0Scope.forUser(scopeSecret), querySecret, new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.AUTHENTICATION);
            assertThat(failure.getMessage())
                    .doesNotContain(API_KEY)
                    .doesNotContain(scopeSecret)
                    .doesNotContain(querySecret)
                    .doesNotContain("error");
            assertThat(Mem0ApiKey.of(API_KEY).toString()).doesNotContain(API_KEY);
            assertThat(provider.toString()).doesNotContain(API_KEY).doesNotContain(scopeSecret);
        }
    }

    @Test
    void listAsync_shouldUseCurrentV3PathAndContract() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            server.enqueueJson(
                    200,
                    "{\"count\":1,\"next\":null,\"previous\":null,\"results\":["
                            + "{\"id\":\"m/1\",\"memory\":\"listed\",\"session_id\":\"run-1\","
                            + "\"created_at\":\"2026-08-13T00:00:00Z\"}]}");
            DefaultRunCancellation cancellation = new DefaultRunCancellation();

            // Act
            List<Mem0Memory> listed = provider.listAsync(Mem0Scope.forUser("user"), 2, 25, cancellation)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(listed.getFirst().runId()).isEqualTo("run-1");
            assertThat(server.requests())
                    .extracting(Mem0TestServer.RecordedRequest::method)
                    .containsExactly("POST");
            assertThat(server.requests().get(0).path()).isEqualTo("/v3/memories/");
            assertThat(server.requests().get(0).query()).isEqualTo("page=2&page_size=25");
        }
    }

    @Test
    void listAsync_shouldRejectAmbiguousCombinedUserAndAgentPagination() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer();
                Mem0ContextProvider provider = provider(server)) {
            Mem0Scope combined =
                    Mem0Scope.builder().userId("user").agentId("agent").build();

            // Act and assert
            assertThatThrownBy(() -> provider.listAsync(combined, 1, 25, new DefaultRunCancellation()))
                    .isInstanceOf(ValidationException.class);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void close_shouldPreserveCallerExecutorsAndRejectFurtherOperations() throws Exception {
        // Arrange
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (Mem0TestServer server = new Mem0TestServer()) {
            Mem0ClientOptions options = Mem0ClientOptions.builder()
                    .endpoint(server.endpoint())
                    .executor(executor)
                    .scheduler(scheduler)
                    .retryOptions(Mem0RetryOptions.builder().maxRetries(0).build())
                    .build();
            Mem0ContextProvider provider = Mem0ContextProvider.builder(
                            Mem0ApiKey.of(API_KEY), Mem0Scope.forUser("user"))
                    .clientOptions(options)
                    .build();

            // Act
            provider.close();
            provider.close();
            Throwable failure =
                    failure(provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation()));

            // Assert
            assertKind(failure, Mem0StorageException.Kind.CLOSED);
            assertThat(executor.isShutdown()).isFalse();
            assertThat(scheduler.isShutdown()).isFalse();
            assertThat(executor.submit(() -> 42).get()).isEqualTo(42);
        } finally {
            scheduler.shutdownNow();
            executor.close();
        }
    }

    @Test
    void close_shouldNotDeadlockWhenCalledFromOwnedExecutorCompletion() throws Exception {
        // Arrange
        try (Mem0TestServer server = new Mem0TestServer()) {
            server.enqueueJson(200, "{\"results\":[]}");
            Mem0ClientOptions options = Mem0ClientOptions.builder()
                    .endpoint(server.endpoint())
                    .closeTimeout(Duration.ofMillis(50))
                    .retryOptions(Mem0RetryOptions.builder().maxRetries(0).build())
                    .build();
            Mem0ContextProvider provider = Mem0ContextProvider.builder(
                            Mem0ApiKey.of(API_KEY), Mem0Scope.forUser("user"))
                    .clientOptions(options)
                    .build();
            CompletableFuture<Void> closedFromCallback = new CompletableFuture<>();

            try {
                // Act
                provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation())
                        .whenComplete((ignored, failure) -> {
                            try {
                                provider.close();
                                closedFromCallback.complete(null);
                            } catch (RuntimeException exception) {
                                closedFromCallback.completeExceptionally(exception);
                            }
                        });

                // Assert
                closedFromCallback.get(2, TimeUnit.SECONDS);
            } finally {
                provider.close();
            }
        }
    }

    @Test
    void rejectedCompletionDispatch_shouldReleasePermitAndCloseTheAttempt() throws Exception {
        // Arrange
        Mem0LimitOptions limits =
                Mem0LimitOptions.builder().maxConcurrentRequests(1).build();
        try (Mem0TestServer server = new Mem0TestServer();
                CompletionRejectingExecutor executor = new CompletionRejectingExecutor()) {
            Mem0ClientOptions options = Mem0ClientOptions.builder()
                    .endpoint(server.endpoint())
                    .executor(executor)
                    .requestTimeout(Duration.ofSeconds(2))
                    .operationTimeout(Duration.ofSeconds(3))
                    .retryOptions(Mem0RetryOptions.builder().maxRetries(0).build())
                    .limitOptions(limits)
                    .build();
            try (Mem0ContextProvider provider = Mem0ContextProvider.builder(
                            Mem0ApiKey.of(API_KEY), Mem0Scope.forUser("user"))
                    .clientOptions(options)
                    .build()) {
                server.enqueue(new Mem0TestServer.Response(
                        200, "application/json", "{\"results\":[]}", Map.of(), Duration.ofMillis(200)));
                CompletionStage<List<Mem0Memory>> first =
                        provider.searchAsync(Mem0Scope.forUser("user"), "query", new DefaultRunCancellation());
                server.awaitRequestCount(1);
                executor.rejectNextCompletion();

                // Act
                Throwable firstFailure = failure(first);
                server.enqueueJson(200, "{\"results\":[]}");
                List<Mem0Memory> second = provider.searchAsync(
                                Mem0Scope.forUser("user"), "query", new DefaultRunCancellation())
                        .toCompletableFuture()
                        .join();

                // Assert
                assertKind(firstFailure, Mem0StorageException.Kind.TRANSPORT);
                assertThat(second).isEmpty();
                assertThat(server.requests()).hasSize(2);
            }
        }
    }

    private static Stream<Arguments> malformedResponses() {
        Mem0LimitOptions defaultLimits = Mem0LimitOptions.defaults();
        return Stream.of(
                Arguments.of("duplicate-key", "{\"results\":[],\"results\":[]}", defaultLimits),
                Arguments.of("trailing-content", "{\"results\":[]}{}", defaultLimits),
                Arguments.of(
                        "nonfinite-number",
                        "{\"results\":[{\"id\":\"one\",\"memory\":\"fact\",\"score\":NaN}]}",
                        defaultLimits),
                Arguments.of(
                        "nesting-depth",
                        "{\"results\":[{\"id\":\"one\",\"memory\":\"fact\",\"score\":0.9,"
                                + "\"metadata\":{\"a\":{\"b\":{\"c\":\"d\"}}}}]}",
                        Mem0LimitOptions.builder().maxNestingDepth(4).build()),
                Arguments.of(
                        "string-length",
                        "{\"results\":[{\"id\":\"one\",\"memory\":\"123456789\",\"score\":0.9}]}",
                        Mem0LimitOptions.builder()
                                .maxStringLength(8)
                                .maxSnippetCharacters(8)
                                .maxMessageCharacters(8)
                                .maxMemoryIdCharacters(8)
                                .build()),
                Arguments.of(
                        "collection-limit",
                        "{\"results\":["
                                + "{\"id\":\"one\",\"memory\":\"1\",\"score\":0.9},"
                                + "{\"id\":\"two\",\"memory\":\"2\",\"score\":0.8},"
                                + "{\"id\":\"three\",\"memory\":\"3\",\"score\":0.7},"
                                + "{\"id\":\"four\",\"memory\":\"4\",\"score\":0.6}]}",
                        Mem0LimitOptions.builder().maxCollectionEntries(3).build()));
    }

    private static Mem0ContextProvider provider(Mem0TestServer server) {
        return provider(server, Mem0LimitOptions.defaults(), 0, Duration.ofSeconds(2));
    }

    private static Mem0ContextProvider provider(
            Mem0TestServer server, Mem0LimitOptions limits, int maxRetries, Duration operationTimeout) {
        Mem0ClientOptions options = Mem0ClientOptions.builder()
                .endpoint(server.endpoint())
                .requestTimeout(Duration.ofSeconds(5))
                .operationTimeout(operationTimeout)
                .initialEventPollDelay(Duration.ofMillis(5))
                .maxEventPollDelay(Duration.ofMillis(10))
                .retryOptions(Mem0RetryOptions.builder()
                        .maxRetries(maxRetries)
                        .initialDelay(Duration.ofMillis(1))
                        .maxDelay(Duration.ofMillis(5))
                        .build())
                .limitOptions(limits)
                .build();
        return Mem0ContextProvider.builder(Mem0ApiKey.of(API_KEY), Mem0Scope.forUser("user"))
                .clientOptions(options)
                .build();
    }

    private static ContextProviderRequest request(String runId) {
        return request(runId, new DefaultRunCancellation());
    }

    private static ContextProviderRequest request(String runId, RunCancellation cancellation) {
        AgentSession session = new AgentSession("session-" + runId);
        Message input = Message.text(Role.USER, "question");
        AgentRunContext runContext = new AgentRunContext(
                runId,
                new AgentMetadata("agent", null, null),
                Instant.now(),
                List.of(input),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(input), List.of(), Map.of(), List.of());
    }

    private static Throwable failure(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("Expected stage failure.");
        } catch (CompletionException exception) {
            Throwable current = exception;
            while (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }
    }

    private static void assertKind(Throwable failure, Mem0StorageException.Kind kind) {
        assertThat(failure).isInstanceOf(Mem0StorageException.class);
        assertThat(((Mem0StorageException) failure).kind()).isEqualTo(kind);
    }

    private static final class RacingCancellation implements RunCancellation {
        private final AtomicBoolean requested = new AtomicBoolean();

        private final AtomicInteger checks = new AtomicInteger();

        private final CompletableFuture<Void> notification = new CompletableFuture<>();

        @Override
        public boolean cancel() {
            if (!requested.compareAndSet(false, true)) {
                return false;
            }
            notification.complete(null);
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            boolean observed = requested.get();
            if (checks.incrementAndGet() == 2 && requested.compareAndSet(false, true)) {
                notification.complete(null);
            }
            return observed;
        }

        @Override
        public CompletionStage<Void> cancelledAsync() {
            return notification.minimalCompletionStage();
        }
    }

    private static final class CompletionRejectingExecutor extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();

        private final AtomicBoolean rejectNextCompletion = new AtomicBoolean();

        private void rejectNextCompletion() {
            rejectNextCompletion.set(true);
        }

        @Override
        public void execute(Runnable command) {
            if (command instanceof CompletableFuture.AsynchronousCompletionTask
                    && rejectNextCompletion.compareAndSet(true, false)) {
                throw new RejectedExecutionException("test completion rejection");
            }
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
