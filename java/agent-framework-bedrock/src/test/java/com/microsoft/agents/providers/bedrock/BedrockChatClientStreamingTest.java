// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BedrockChatClientStreamingTest {
    @Test
    void streamingRun_shouldPublishOneTerminalAndRejectMissingOrLateUpdates() {
        ScriptedTransport valid = new ScriptedTransport(List.of(text("one"), terminal()));
        try (BedrockChatClient client = client(valid, false, 8)) {
            assertThat(collect(client.completeStreaming(request())).join()).containsExactly(text("one"), terminal());
        }

        ScriptedTransport late = new ScriptedTransport(List.of(terminal(), text("late")));
        try (BedrockChatClient client = client(late, false, 8)) {
            assertThatThrownBy(
                            () -> collect(client.completeStreaming(request())).join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("update_after_terminal");
        }

        ScriptedTransport missing = new ScriptedTransport(List.of(text("only")));
        try (BedrockChatClient client = client(missing, false, 8)) {
            assertThatThrownBy(
                            () -> collect(client.completeStreaming(request())).join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("missing_terminal");
        }
    }

    @Test
    void streamingRun_shouldBoundBufferPropagateCancellationAndHonorOwnership() {
        ScriptedTransport overflow = new ScriptedTransport(List.of(text("one"), text("two"), terminal()));
        try (BedrockChatClient client = client(overflow, false, 1)) {
            assertThatThrownBy(() -> failureWithoutDemand(client.completeStreaming(request()))
                            .join())
                    .hasRootCauseInstanceOf(BedrockProviderException.class)
                    .rootCause()
                    .extracting("kind")
                    .isEqualTo("stream_buffer_overflow");
        }

        StallingTransport stalling = new StallingTransport();
        try (BedrockChatClient client = client(stalling, false, 8)) {
            AtomicReference<Flow.Subscription> outer = new AtomicReference<>();
            client.completeStreaming(request()).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    outer.set(subscription);
                    subscription.request(1);
                }

                @Override
                public void onNext(ChatResponseUpdate item) {}

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });
            outer.get().cancel();
            assertThat(stalling.cancelled).isTrue();
        }
        assertThat(stalling.closed).isFalse();

        StallingTransport owned = new StallingTransport();
        client(owned, true, 8).close();
        assertThat(owned.closed).isTrue();
    }

    private static BedrockChatClient client(BedrockTransport transport, boolean owns, int maxBufferedUpdates) {
        return BedrockChatClient.builder()
                .options(BedrockChatClientOptions.builder()
                        .model("test-model")
                        .maxBufferedUpdates(maxBufferedUpdates)
                        .build())
                .transport(transport, owns)
                .build();
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty());
    }

    private static ChatResponseUpdate text(String value) {
        return ChatResponseUpdate.builder()
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(value)))
                .build();
    }

    private static ChatResponseUpdate terminal() {
        return ChatResponseUpdate.builder()
                .role(Role.ASSISTANT)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
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

    private static CompletableFuture<Throwable> failureWithoutDemand(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {}

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.completeExceptionally(new AssertionError("Expected failure."));
            }
        });
        return result;
    }

    private static final class ScriptedTransport implements BedrockTransport {
        private final List<ChatResponseUpdate> updates;

        private ScriptedTransport(List<ChatResponseUpdate> updates) {
            this.updates = updates;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new AssertionError("finite transport called"));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(long count) {
                    if (done) {
                        return;
                    }
                    done = true;
                    updates.forEach(subscriber::onNext);
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }

    private static final class StallingTransport implements BedrockTransport {
        private final AtomicBoolean closedState = new AtomicBoolean();

        private boolean cancelled;

        private boolean closed;

        @Override
        public CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new AssertionError("finite transport called"));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }

        @Override
        public void close() {
            if (closedState.compareAndSet(false, true)) {
                closed = true;
            }
        }
    }
}
