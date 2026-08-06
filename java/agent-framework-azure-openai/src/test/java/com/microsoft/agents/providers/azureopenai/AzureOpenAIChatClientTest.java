// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

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
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.providers.openai.OpenAITransport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AzureOpenAIChatClientTest {
    @Test
    void finiteCompletion_shouldUseProductionMappingAndAzureMetadata() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AzureOpenAIChatClient client = client(transport, 8);

        // Act
        ChatResponse response =
                client.completeAsync(request("hello")).toCompletableFuture().join();

        // Assert
        assertThat(transport.request.model()).isEqualTo("deployment");
        assertThat(transport.request.input()).singleElement().isInstanceOf(OpenAITransport.MessageInput.class);
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.metadata())
                .containsEntry("azureOpenai.requestId", StateValue.string("request-1"))
                .containsEntry("azureOpenai.deployment", StateValue.string("deployment"));
        assertThat(response.metadata()).doesNotContainKey("openai.requestId");
    }

    @Test
    void streaming_shouldHonorDemandAndPropagateCancellation() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AzureOpenAIChatClient client = client(transport, 3);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        // Act
        client.completeStreaming(request("hello"), cancellation).subscribe(new Flow.Subscriber<>() {
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
                terminal.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                terminal.complete(null);
            }
        });
        int beforeMoreDemand = updates.size();
        subscription.get().request(2);
        terminal.join();

        // Assert
        assertThat(beforeMoreDemand).isEqualTo(1);
        assertThat(updates).hasSize(3);
        assertThat(updates.get(1).text()).isEqualTo("done");

        // Act
        transport.pending = true;
        AtomicReference<Flow.Subscription> pendingSubscription = new AtomicReference<>();
        client.completeStreaming(request("wait"), cancellation).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                pendingSubscription.set(value);
                value.request(1);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        pendingSubscription.get().cancel();

        // Assert
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(transport.upstreamCancelled).isTrue();
    }

    @Test
    void unsupportedContentAndTransportFailures_shouldMapToSanitizedAzureExceptions() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AzureOpenAIChatClient client = client(transport, 8);
        ChatClientRequest unsupported = new ChatClientRequest(
                List.of(new Message(Role.TOOL, List.of(new TextContent("not-a-tool-result")))), ChatOptions.empty());

        // Act / Assert
        assertThatThrownBy(() ->
                        client.completeAsync(unsupported).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> assertThat(failure.kind())
                        .isEqualTo(AzureOpenAIProviderException.Kind.UNSUPPORTED_CONTENT));

        transport.failure = new IllegalStateException("credential=" + AzureOpenAIChatClientOptionsTest.class.getName());
        assertThatThrownBy(() -> client.completeAsync(request("failure"))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> assertThat(failure.getMessage())
                        .isEqualTo("Azure OpenAI request failed [code transport_error]."));
    }

    @Test
    void continuation_shouldRejectConversationPrefixBeforeFiniteOrStreamingTransport() {
        // Arrange
        RecordingTransport transport = new RecordingTransport();
        AzureOpenAIChatClient client = client(transport, 8);
        ChatClientRequest conversation = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder().conversationId("conv_123").build());

        // Act / Assert
        assertThatThrownBy(() ->
                        client.completeAsync(conversation).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AzureOpenAIProviderException.Kind.UNSUPPORTED_OPTION);
                    assertThat(failure.serviceCode()).contains("conversation_not_supported");
                });
        assertThat(streamFailure(client.completeStreaming(conversation)).join())
                .isInstanceOfSatisfying(AzureOpenAIProviderException.class, failure -> assertThat(failure.kind())
                        .isEqualTo(AzureOpenAIProviderException.Kind.UNSUPPORTED_OPTION));
        assertThat(transport.request).isNull();
    }

    @Test
    void close_shouldRespectInjectedTransportOwnership() {
        RecordingTransport callerOwned = new RecordingTransport();
        RecordingTransport transferred = new RecordingTransport();
        AzureOpenAIChatClient first = AzureOpenAIChatClient.builder()
                .options(options(8))
                .transport(callerOwned)
                .build();
        AzureOpenAIChatClient second = AzureOpenAIChatClient.builder()
                .options(options(8))
                .transport(transferred, true)
                .build();

        first.close();
        second.close();

        assertThat(callerOwned.closed).isFalse();
        assertThat(transferred.closed).isTrue();
    }

    private static AzureOpenAIChatClient client(RecordingTransport transport, int capacity) {
        return AzureOpenAIChatClient.builder()
                .options(options(capacity))
                .transport(transport)
                .build();
    }

    private static AzureOpenAIChatClientOptions options(int capacity) {
        return AzureOpenAIChatClientOptions.builder()
                .endpoint("https://resource.openai.azure.com")
                .deployment("deployment")
                .apiKey("unit-test-key")
                .maxBufferedUpdates(capacity)
                .build();
    }

    private static ChatClientRequest request(String text) {
        return new ChatClientRequest(List.of(Message.text(Role.USER, text)), ChatOptions.empty());
    }

    private static CompletableFuture<Throwable> streamFailure(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {
                failure.complete(throwable);
            }

            @Override
            public void onComplete() {
                failure.completeExceptionally(new AssertionError("Expected stream failure."));
            }
        });
        return failure;
    }

    private static OpenAITransport.Response response() {
        return new OpenAITransport.Response(
                "response-1",
                null,
                "deployment",
                Instant.EPOCH,
                OpenAITransport.ResponseStatus.COMPLETED,
                List.of(new OpenAITransport.TextOutput("message-1", "done", false, Map.of())),
                null,
                Map.of(),
                "request-1",
                null,
                null);
    }

    private static final class RecordingTransport implements AzureOpenAITransport {
        private OpenAITransport.Request request;

        private RuntimeException failure;

        private boolean pending;

        private boolean closed;

        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            this.request = request;
            return failure == null
                    ? CompletableFuture.completedFuture(response())
                    : CompletableFuture.failedFuture(failure);
        }

        @Override
        public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
                OpenAITransport.Request request, RunCancellation cancellation) {
            this.request = request;
            upstreamCancelled.set(false);
            if (pending) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {}

                    @Override
                    public void cancel() {
                        upstreamCancelled.set(true);
                    }
                });
            }
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean emitted;

                @Override
                public void request(long count) {
                    if (emitted || count <= 0) {
                        return;
                    }
                    emitted = true;
                    subscriber.onNext(new OpenAITransport.ResponseStarted(
                            0,
                            "response-1",
                            null,
                            "deployment",
                            Instant.EPOCH,
                            "request-1",
                            OpenAITransport.ResponseStatus.IN_PROGRESS));
                    subscriber.onNext(new OpenAITransport.TextDelta(1, "message-1", "done", Map.of()));
                    subscriber.onNext(new OpenAITransport.ResponseCompleted(2, response()));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    upstreamCancelled.set(true);
                }
            });
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
