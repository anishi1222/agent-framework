// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2AErrorCode;
import com.microsoft.agents.protocols.a2a.A2AProtocolException;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.AgentCapabilities;
import com.microsoft.agents.protocols.a2a.AgentCard;
import com.microsoft.agents.protocols.a2a.AgentInterface;
import com.microsoft.agents.protocols.a2a.AgentSkill;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.Message;
import com.microsoft.agents.protocols.a2a.PushNotificationConfig;
import com.microsoft.agents.protocols.a2a.Role;
import com.microsoft.agents.protocols.a2a.SendMessageConfiguration;
import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TextPart;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class A2AServiceTest {
    private static final A2APrincipal ALICE = new A2APrincipal("alice", "tenant-a");

    private static final A2APrincipal BOB = new A2APrincipal("bob", "tenant-a");

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void finiteSend_shouldCompleteTaskAndDeduplicateMessageId() {
        // Arrange
        try (A2AService service = service(new EchoExecutor(), false)) {
            SendMessageRequest request = request("hello", "message-1", false);

            // Act
            Task first = (Task) service.sendMessageAsync(ALICE, request)
                    .toCompletableFuture()
                    .join();
            Task duplicate = (Task) service.sendMessageAsync(ALICE, request)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(first.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(first.artifacts())
                    .singleElement()
                    .satisfies(
                            artifact -> assertThat(((TextPart) artifact.parts().getFirst()).text())
                                    .isEqualTo("echo:hello"));
            assertThat(duplicate).isEqualTo(first);
            assertThat(first.history()).isEmpty();
            assertThat(service.getTaskAsync(ALICE, new A2ARequests.GetTask(first.id()))
                            .toCompletableFuture()
                            .join()
                            .history())
                    .hasSize(1);
        }
    }

    @Test
    void taskLookup_shouldNeverUseTaskIdAsAuthorization() {
        // Arrange
        try (A2AService service = service(new EchoExecutor(), false)) {
            Task task = (Task) service.sendMessageAsync(ALICE, request("secret", "message-1", false))
                    .toCompletableFuture()
                    .join();

            // Act / Assert
            assertThatThrownBy(() -> service.getTaskAsync(BOB, new A2ARequests.GetTask(task.id()))
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(A2AProtocolException.class)
                    .rootCause()
                    .extracting(failure -> ((A2AProtocolException) failure).errorCode())
                    .isEqualTo(java.util.Optional.of(A2AErrorCode.TASK_NOT_FOUND));
        }
    }

    @Test
    void listAndPushCrud_shouldRespectCursorAndTaskOwnership() {
        // Arrange
        try (A2AService service = service(new EchoExecutor(), true)) {
            Task first = (Task) service.sendMessageAsync(ALICE, request("one", "message-1", false))
                    .toCompletableFuture()
                    .join();
            service.sendMessageAsync(ALICE, request("two", "message-2", false))
                    .toCompletableFuture()
                    .join();
            A2ARequests.ListTasks pageRequest = new A2ARequests.ListTasks(null, null, 1, null, 0, null, true, null);

            // Act
            A2ACursorPage<Task> page1 = service.listTasksAsync(ALICE, pageRequest)
                    .toCompletableFuture()
                    .join();
            A2ACursorPage<Task> page2 = service.listTasksAsync(ALICE, pageRequest.next(page1.nextPageToken()))
                    .toCompletableFuture()
                    .join();
            PushNotificationConfig config = new PushNotificationConfig(
                    "push-1", first.id(), URI.create("https://callback.test/a2a"), "secret", null, null);
            service.createPushNotificationConfigAsync(ALICE, config)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(page1.items()).hasSize(1);
            assertThat(page1.hasNextPage()).isTrue();
            assertThat(page2.items()).hasSize(1);
            assertThat(service.getPushNotificationConfigAsync(
                                    ALICE, new A2ARequests.GetPushConfig(first.id(), config.id(), null))
                            .toCompletableFuture()
                            .join())
                    .isEqualTo(config);
            assertThat(service.listPushNotificationConfigsAsync(ALICE, new A2ARequests.ListPushConfigs(first.id()))
                            .toCompletableFuture()
                            .join()
                            .items())
                    .containsExactly(config);
            assertThat(service.deletePushNotificationConfigAsync(
                                    ALICE, new A2ARequests.DeletePushConfig(first.id(), config.id(), null))
                            .toCompletableFuture()
                            .join())
                    .isTrue();
            assertThat(service.deletePushNotificationConfigAsync(
                                    ALICE, new A2ARequests.DeletePushConfig(first.id(), config.id(), null))
                            .toCompletableFuture()
                            .join())
                    .isTrue();
        }
    }

    @Test
    void inputRequiredContinuation_shouldReuseTaskAndContextWithoutDuplicateHistory() {
        // Arrange
        InputThenCompleteExecutor executor = new InputThenCompleteExecutor();
        try (A2AService service = service(executor, false)) {
            Task interrupted = (Task) service.sendMessageAsync(ALICE, request("start", "message-1", false))
                    .toCompletableFuture()
                    .join();
            Message continuation = Message.builder(Role.ROLE_USER)
                    .messageId("message-2")
                    .contextId(interrupted.contextId())
                    .taskId(interrupted.id())
                    .parts(List.of(new TextPart("approved")))
                    .build();

            // Act
            Task completed = (Task) service.sendMessageAsync(ALICE, new SendMessageRequest(continuation))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(interrupted.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
            assertThat(completed.id()).isEqualTo(interrupted.id());
            assertThat(completed.contextId()).isEqualTo(interrupted.contextId());
            assertThat(completed.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(completed.history()).extracting(Message::messageId).containsExactly("message-1", "message-2");
            assertThat(executor.calls()).isEqualTo(2);
        }
    }

    @Test
    void cancel_shouldSignalActiveExecutionAndReachCanceledState() {
        // Arrange
        WaitingExecutor executor = new WaitingExecutor();
        try (A2AService service = service(executor, false)) {
            Task submitted = (Task) service.sendMessageAsync(ALICE, request("wait", "message-1", true))
                    .toCompletableFuture()
                    .join();
            executor.started().orTimeout(5, TimeUnit.SECONDS).join();

            // Act
            Task canceled = service.cancelTaskAsync(ALICE, new A2ARequests.CancelTask(submitted.id()))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(canceled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
            assertThat(executor.cancelObserved().orTimeout(5, TimeUnit.SECONDS).join())
                    .isTrue();
        }
    }

    @Test
    void resubscribe_shouldEmitCurrentTaskFirstAndNotClaimReplay() throws Exception {
        // Arrange
        try (A2AService service = service(new InputThenCompleteExecutor(), false)) {
            Task task = (Task) service.sendMessageAsync(ALICE, request("start", "message-1", false))
                    .toCompletableFuture()
                    .join();
            RecordingSubscriber subscriber = new RecordingSubscriber();

            // Act
            service.subscribeToTask(ALICE, new A2ARequests.SubscribeToTask(task.id()))
                    .subscribe(subscriber);
            subscriber.completed().get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(subscriber.events()).hasSize(1);
            assertThat(subscriber.events().getFirst()).isInstanceOf(Task.class);
            assertThat(((Task) subscriber.events().getFirst()).id()).isEqualTo(task.id());
        }
    }

    @Test
    void delayedStore_shouldAllowUnrelatedPreparationAndDeduplicateInFlightMessage() throws Exception {
        // Arrange
        DelayedCreateTaskStore store = new DelayedCreateTaskStore();
        CountingEchoExecutor executor = new CountingEchoExecutor();
        try (A2AService service = service(executor, false, store)) {
            SendMessageRequest aliceRequest = request("alice", "message-1", false);
            CompletionStage<com.microsoft.agents.protocols.a2a.SendMessageResult> first =
                    service.sendMessageAsync(ALICE, aliceRequest);
            store.delayedCreateStarted().get(5, TimeUnit.SECONDS);

            // Act
            CompletionStage<com.microsoft.agents.protocols.a2a.SendMessageResult> duplicate =
                    service.sendMessageAsync(ALICE, aliceRequest);
            CompletionStage<com.microsoft.agents.protocols.a2a.SendMessageResult> unrelated =
                    service.sendMessageAsync(BOB, request("bob", "message-2", false));
            try {
                Task unrelatedTask = (Task) unrelated.toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertThat(unrelatedTask.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
                assertThat(first.toCompletableFuture().isDone()).isFalse();
                assertThat(duplicate.toCompletableFuture().isDone()).isFalse();
            } finally {
                store.releaseDelayedCreate();
            }
            Task firstTask = (Task) first.toCompletableFuture().get(5, TimeUnit.SECONDS);
            Task duplicateTask = (Task) duplicate.toCompletableFuture().get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(duplicateTask.id()).isEqualTo(firstTask.id());
            assertThat(store.createCalls()).isEqualTo(2);
            assertThat(executor.calls()).isEqualTo(2);
        }
    }

    private static A2AService service(A2AExecutor executor, boolean push) {
        return service(executor, push, new InMemoryA2ATaskStore(100));
    }

    private static A2AService service(A2AExecutor executor, boolean push, A2ATaskStore taskStore) {
        AgentCard card = card(push);
        A2AService.Builder builder = A2AService.builder(card, executor)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .taskStore(taskStore);
        if (push) {
            builder.pushStore(new InMemoryA2APushNotificationConfigStore(100));
        }
        return builder.build();
    }

    private static AgentCard card(boolean push) {
        return AgentCard.builder("test", "test agent", "1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(push)
                        .build())
                .skills(List.of(AgentSkill.builder("echo", "Echo", "Echoes input")
                        .tags(List.of("test"))
                        .build()))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("http://127.0.0.1:1/a2a"))))
                .build();
    }

    private static SendMessageRequest request(String text, String messageId, boolean returnImmediately) {
        Message message = Message.builder(Role.ROLE_USER)
                .messageId(messageId)
                .parts(List.of(new TextPart(text)))
                .build();
        return new SendMessageRequest(
                message,
                new SendMessageConfiguration(List.of("text/plain"), 0, null, returnImmediately),
                Map.of(),
                null);
    }

    private static class EchoExecutor implements A2AExecutor {
        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context,
                A2AEventSink sink,
                com.microsoft.agents.core.RunCancellation cancellation) {
            String input = ((TextPart) context.request().message().parts().getFirst()).text();
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                    .thenCompose(ignored -> sink.addArtifactAsync(
                            Artifact.builder(context.task().id() + "-result")
                                    .parts(List.of(new TextPart("echo:" + input)))
                                    .build(),
                            false,
                            true,
                            Map.of()))
                    .thenCompose(ignored -> sink.updateStatusAsync(TaskState.TASK_STATE_COMPLETED, null))
                    .thenApply(ignored -> null);
        }
    }

    private static final class CountingEchoExecutor extends EchoExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context,
                A2AEventSink sink,
                com.microsoft.agents.core.RunCancellation cancellation) {
            calls.incrementAndGet();
            return super.executeAsync(context, sink, cancellation);
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class DelayedCreateTaskStore implements A2ATaskStore {
        private final InMemoryA2ATaskStore delegate = new InMemoryA2ATaskStore(100);

        private final CompletableFuture<Void> delayedCreateStarted = new CompletableFuture<>();

        private final CompletableFuture<Void> releaseDelayedCreate = new CompletableFuture<>();

        private final AtomicBoolean delayAlice = new AtomicBoolean(true);

        private final AtomicInteger createCalls = new AtomicInteger();

        @Override
        public CompletionStage<Task> createAsync(A2APrincipal principal, Task task) {
            createCalls.incrementAndGet();
            if ("alice".equals(principal.principalId()) && delayAlice.compareAndSet(true, false)) {
                delayedCreateStarted.complete(null);
                return releaseDelayedCreate.thenCompose(ignored -> delegate.createAsync(principal, task));
            }
            return delegate.createAsync(principal, task);
        }

        @Override
        public CompletionStage<Optional<Task>> getAsync(A2APrincipal principal, String taskId) {
            return delegate.getAsync(principal, taskId);
        }

        @Override
        public CompletionStage<Task> updateAsync(A2APrincipal principal, Task task, TaskState expectedState) {
            return delegate.updateAsync(principal, task, expectedState);
        }

        @Override
        public CompletionStage<A2ACursorPage<Task>> listAsync(A2APrincipal principal, A2ARequests.ListTasks request) {
            return delegate.listAsync(principal, request);
        }

        @Override
        public CompletionStage<Boolean> deleteAsync(A2APrincipal principal, String taskId) {
            return delegate.deleteAsync(principal, taskId);
        }

        private CompletableFuture<Void> delayedCreateStarted() {
            return delayedCreateStarted;
        }

        private void releaseDelayedCreate() {
            releaseDelayedCreate.complete(null);
        }

        private int createCalls() {
            return createCalls.get();
        }
    }

    private static final class InputThenCompleteExecutor implements A2AExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context,
                A2AEventSink sink,
                com.microsoft.agents.core.RunCancellation cancellation) {
            int call = calls.incrementAndGet();
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                    .thenCompose(ignored -> call == 1
                            ? sink.updateStatusAsync(
                                    TaskState.TASK_STATE_INPUT_REQUIRED,
                                    Message.builder(Role.ROLE_AGENT)
                                            .parts(List.of(new TextPart("Approve?")))
                                            .build())
                            : sink.updateStatusAsync(TaskState.TASK_STATE_COMPLETED, null))
                    .thenApply(ignored -> null);
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class WaitingExecutor implements A2AExecutor {
        private final CompletableFuture<Void> started = new CompletableFuture<>();

        private final CompletableFuture<Boolean> cancelObserved = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> executeAsync(
                A2AExecutionContext context,
                A2AEventSink sink,
                com.microsoft.agents.core.RunCancellation cancellation) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null).thenCompose(ignored -> {
                started.complete(null);
                RunCancellations.register(cancellation, () -> {
                    cancelObserved.complete(true);
                    result.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
                });
                return result;
            });
        }

        private CompletableFuture<Void> started() {
            return started;
        }

        private CompletableFuture<Boolean> cancelObserved() {
            return cancelObserved;
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final List<A2AStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completed.complete(null);
        }

        private List<A2AStreamEvent> events() {
            return List.copyOf(events);
        }

        private CompletableFuture<Void> completed() {
            return completed;
        }
    }
}
