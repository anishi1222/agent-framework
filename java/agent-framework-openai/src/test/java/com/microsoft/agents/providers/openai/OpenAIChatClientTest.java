// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class OpenAIChatClientTest {
    @Test
    void finiteCompletion_shouldUseProductionRequestAndResponseMappers() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        transport.finite = (request, cancellation) -> CompletableFuture.completedFuture(response("done"));
        OpenAIChatClient client = client(transport, 8);

        // Act
        ChatResponse response =
                client.completeAsync(request("hello")).toCompletableFuture().join();

        // Assert
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.responseId()).isEqualTo("response-1");
        assertThat(transport.requests).singleElement().satisfies(mapped -> {
            assertThat(mapped.model()).isEqualTo("model-1");
            assertThat(mapped.input()).hasSize(1);
        });
    }

    @Test
    void finiteCompletion_shouldCancelUnderlyingStageAndRemainTyped() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        CompletableFuture<OpenAITransport.Response> pending = new CompletableFuture<>();
        transport.finite = (request, cancellation) -> pending;
        OpenAIChatClient client = client(transport, 8);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        CompletionStage<ChatResponse> result = client.completeAsync(request("wait"), cancellation);
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> result.toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(pending).isCancelled();
    }

    @Test
    void streaming_shouldRemainColdAndMapUpdatesAfterSubscription() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AtomicInteger subscriptions = new AtomicInteger();
        transport.streaming = (request, cancellation) ->
                new ScriptedPublisher(streamEvents("hello"), subscriptions, new AtomicBoolean());
        OpenAIChatClient client = client(transport, 8);

        // Act
        Flow.Publisher<ChatResponseUpdate> publisher = client.completeStreaming(request("hello"));
        int beforeSubscription = subscriptions.get();
        List<ChatResponseUpdate> updates = collect(publisher).join();

        // Assert
        assertThat(beforeSubscription).isZero();
        assertThat(subscriptions).hasValue(1);
        assertThat(updates).extracting(ChatResponseUpdate::text).containsExactly("", "hello", "");
        assertThat(updates.getLast().finishReason()).isNotNull();
    }

    @Test
    void streaming_shouldHonorDownstreamDemandWithBoundedRetention() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        transport.streaming = (request, cancellation) ->
                new ScriptedPublisher(streamEvents("hello"), new AtomicInteger(), new AtomicBoolean());
        OpenAIChatClient client = client(transport, 2);
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        CompletableFuture<Void> completed = new CompletableFuture<>();

        // Act
        client.completeStreaming(request("hello")).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(null);
            }
        });
        int deliveredBeforeMoreDemand = updates.size();
        subscription.get().request(2);
        completed.join();

        // Assert
        assertThat(deliveredBeforeMoreDemand).isEqualTo(1);
        assertThat(updates).hasSize(3);
    }

    @Test
    void streaming_shouldFailOnBoundedOverflowAndCancelUpstream() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        transport.streaming = (request, cancellation) ->
                new ScriptedPublisher(streamEvents("hello"), new AtomicInteger(), upstreamCancelled);
        OpenAIChatClient client = client(transport, 1);
        CompletableFuture<Throwable> failure = new CompletableFuture<>();

        // Act
        client.completeStreaming(request("hello")).subscribe(new NoDemandSubscriber(failure));

        // Assert
        assertThat(failure.join()).isInstanceOf(OpenAIStreamingBufferOverflowException.class);
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void streamingSubscriptionCancellation_shouldPropagateToRunAndUpstream() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        transport.streaming = (request, cancellation) -> new PendingPublisher(upstreamCancelled);
        OpenAIChatClient client = client(transport, 8);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        // Act
        client.completeStreaming(request("wait"), cancellation).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        subscription.get().cancel();

        // Assert
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void streaming_shouldRejectSecondSubscriberAndMissingTerminalEvent() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        transport.streaming = (request, cancellation) -> new ScriptedPublisher(
                List.of(new OpenAITransport.TextDelta(0, "message", "orphan", java.util.Map.of())),
                new AtomicInteger(),
                new AtomicBoolean());
        OpenAIChatClient client = client(transport, 8);
        Flow.Publisher<ChatResponseUpdate> publisher = client.completeStreaming(request("hello"));

        // Act
        Throwable firstFailure = collectFailure(publisher).join();
        Throwable secondFailure = collectFailure(publisher).join();

        // Assert
        assertThat(firstFailure).isInstanceOf(OpenAIProtocolException.class).hasMessageContaining("without a terminal");
        assertThat(secondFailure).isInstanceOf(IllegalStateException.class).hasMessageContaining("one subscriber");
    }

    @Test
    void close_shouldRespectInjectedOwnershipAndRejectNewWork() {
        // Arrange
        RecordingTransport callerOwned = new RecordingTransport();
        OpenAIChatClient callerOwnedClient = OpenAIChatClient.builder()
                .options(options(8))
                .transport(callerOwned)
                .build();
        RecordingTransport transferred = new RecordingTransport();
        OpenAIChatClient transferredClient = OpenAIChatClient.builder()
                .options(options(8))
                .transport(transferred, true)
                .build();

        // Act
        callerOwnedClient.close();
        transferredClient.close();

        // Assert
        assertThat(callerOwned.closed).isFalse();
        assertThat(transferred.closed).isTrue();
        assertThatThrownBy(() -> callerOwnedClient
                        .completeAsync(request("late"))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OpenAISdkException.class);
    }

    private static OpenAIChatClient client(RecordingTransport transport, int maxBufferedUpdates) {
        return OpenAIChatClient.builder()
                .options(options(maxBufferedUpdates))
                .transport(transport)
                .build();
    }

    private static OpenAIChatClientOptions options(int maxBufferedUpdates) {
        return OpenAIChatClientOptions.builder()
                .model("model-1")
                .maxBufferedUpdates(maxBufferedUpdates)
                .build();
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(List.of(Message.text(Role.USER, text)), ChatOptions.empty());
    }

    private static OpenAITransport.Response response(String text) {
        return new OpenAITransport.Response(
                "response-1",
                null,
                "model-1",
                Instant.EPOCH,
                OpenAITransport.ResponseStatus.COMPLETED,
                List.of(new OpenAITransport.TextOutput("message-1", text, false, java.util.Map.of())),
                null,
                java.util.Map.of(),
                null,
                null,
                null);
    }

    private static List<OpenAITransport.StreamEvent> streamEvents(String text) {
        return List.of(
                new OpenAITransport.ResponseStarted(
                        0,
                        "response-1",
                        null,
                        "model-1",
                        Instant.EPOCH,
                        null,
                        OpenAITransport.ResponseStatus.IN_PROGRESS),
                new OpenAITransport.TextDelta(1, "message-1", text, java.util.Map.of()),
                new OpenAITransport.ResponseCompleted(2, response(text)));
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        CopyOnWriteArrayList<ChatResponseUpdate> updates = new CopyOnWriteArrayList<>();
        CompletableFuture<List<ChatResponseUpdate>> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(updates));
            }
        });
        return result;
    }

    private static CompletableFuture<Throwable> collectFailure(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {
                result.complete(throwable);
            }

            @Override
            public void onComplete() {
                result.completeExceptionally(new AssertionError("Expected stream failure."));
            }
        });
        return result;
    }

    private static final class NoDemandSubscriber implements Flow.Subscriber<ChatResponseUpdate> {
        private final CompletableFuture<Throwable> failure;

        private NoDemandSubscriber(CompletableFuture<Throwable> failure) {
            this.failure = failure;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {}

        @Override
        public void onNext(ChatResponseUpdate item) {}

        @Override
        public void onError(Throwable throwable) {
            failure.complete(throwable);
        }

        @Override
        public void onComplete() {
            failure.completeExceptionally(new AssertionError("Expected overflow."));
        }
    }

    private static final class RecordingTransport implements OpenAITransport {
        private final List<OpenAITransport.Request> requests = new CopyOnWriteArrayList<>();

        private BiFunction<OpenAITransport.Request, RunCancellation, CompletionStage<OpenAITransport.Response>> finite =
                (request, cancellation) -> CompletableFuture.completedFuture(response("default"));

        private BiFunction<OpenAITransport.Request, RunCancellation, Flow.Publisher<OpenAITransport.StreamEvent>>
                streaming = (request, cancellation) ->
                        new ScriptedPublisher(streamEvents("default"), new AtomicInteger(), new AtomicBoolean());

        private boolean closed;

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            return finite.apply(request, cancellation);
        }

        @Override
        public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            return streaming.apply(request, cancellation);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class ScriptedPublisher implements Flow.Publisher<OpenAITransport.StreamEvent> {
        private final List<OpenAITransport.StreamEvent> events;

        private final AtomicInteger subscriptions;

        private final AtomicBoolean cancelled;

        private ScriptedPublisher(
                List<OpenAITransport.StreamEvent> events, AtomicInteger subscriptions, AtomicBoolean cancelled) {
            this.events = events;
            this.subscriptions = subscriptions;
            this.cancelled = cancelled;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super OpenAITransport.StreamEvent> subscriber) {
            subscriptions.incrementAndGet();
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean emitted = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (count <= 0 || !emitted.compareAndSet(false, true)) {
                        return;
                    }
                    for (OpenAITransport.StreamEvent event : events) {
                        if (cancelled.get()) {
                            return;
                        }
                        subscriber.onNext(event);
                    }
                    if (!cancelled.get()) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }

    private static final class PendingPublisher implements Flow.Publisher<OpenAITransport.StreamEvent> {
        private final AtomicBoolean cancelled;

        private PendingPublisher(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super OpenAITransport.StreamEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }
}
