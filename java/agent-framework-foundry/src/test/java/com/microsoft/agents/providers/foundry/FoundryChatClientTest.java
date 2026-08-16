// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.credential.AccessToken;
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
import com.microsoft.agents.tools.ToolMode;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import reactor.core.publisher.Mono;

class FoundryChatClientTest {
    @Test
    void finiteModelCompletion_shouldMapConversationOptionsAndMetadata() {
        RecordingTransport transport = new RecordingTransport();
        FoundryChatClient client = client(modelOptions(), transport, 8);
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .conversationId("conversation-1")
                        .temperature(0.2)
                        .build());

        ChatResponse response =
                client.completeAsync(request).toCompletableFuture().join();

        assertThat(transport.request.conversationId()).isEqualTo("conversation-1");
        assertThat(transport.request.previousResponseId()).isNull();
        assertThat(transport.request.temperature()).isEqualTo(0.2);
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.metadata())
                .containsEntry("foundry.requestId", StateValue.string("request-1"))
                .containsEntry("foundry.surface", StateValue.string("model"));
    }

    @Test
    void agentCompletion_shouldKeepContinuationButStripServerOwnedOverrides() {
        RecordingTransport transport = new RecordingTransport();
        FoundryChatClient client = client(agentOptions(), transport, 8);
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .model("ignored-model")
                        .temperature(0.4)
                        .topP(0.7)
                        .maxTokens(321)
                        .user("user-42")
                        .store(true)
                        .instructions("ignored instructions")
                        .conversationId("conversation-2")
                        .build(),
                List.of(),
                ToolMode.AUTO,
                null);

        client.completeAsync(request).toCompletableFuture().join();

        assertThat(transport.request.conversationId()).isEqualTo("conversation-2");
        assertThat(transport.request.instructions()).isNull();
        assertThat(transport.request.temperature()).isNull();
        assertThat(transport.request.topP()).isNull();
        assertThat(transport.request.tools()).isEmpty();
        assertThat(transport.request.maxOutputTokens()).isEqualTo(321);
        assertThat(transport.request.user()).isEqualTo("user-42");
        assertThat(transport.request.store()).isTrue();
    }

    @Test
    void previousResponseModeAndDefaultConversation_shouldRemainAdapterOwned() {
        RecordingTransport transport = new RecordingTransport();
        FoundryChatClientOptions options = FoundryChatClientOptions.builder()
                .projectEndpoint(endpoint())
                .model("deployment")
                .tokenCredential(context ->
                        Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1))))
                .defaultConversationId("response-1")
                .continuationMode(FoundryContinuationMode.PREVIOUS_RESPONSE)
                .build();
        FoundryChatClient client = client(options, transport, 8);

        client.completeAsync(new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty()))
                .toCompletableFuture()
                .join();

        assertThat(transport.request.previousResponseId()).isEqualTo("response-1");
        assertThat(transport.request.conversationId()).isNull();
    }

    @Test
    void streaming_shouldHonorDemandCancellationAndSanitizeFailures() {
        RecordingTransport transport = new RecordingTransport();
        FoundryChatClient client = client(modelOptions(), transport, 3);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        CompletableFuture<Void> terminal = new CompletableFuture<>();

        client.completeStreaming(request()).subscribe(new Flow.Subscriber<>() {
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

        assertThat(beforeMoreDemand).isEqualTo(1);
        assertThat(updates).hasSize(3);

        transport.pending = true;
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> pending = new AtomicReference<>();
        client.completeStreaming(request(), cancellation).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                pending.set(value);
                value.request(1);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        pending.get().cancel();
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(transport.upstreamCancelled).isTrue();

        transport.pending = false;
        ChatClientRequest unsupported = new ChatClientRequest(
                List.of(new Message(Role.TOOL, List.of(new TextContent("not-a-tool-result")))), ChatOptions.empty());
        assertThatThrownBy(() ->
                        client.completeAsync(unsupported).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(
                        FoundryProviderException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(FoundryProviderException.Kind.UNSUPPORTED_CONTENT));

        transport.failure = new IllegalStateException("credential-secret");
        assertThatThrownBy(() ->
                        client.completeAsync(request()).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOfSatisfying(FoundryProviderException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FoundryProviderException.Kind.TRANSPORT);
                    assertThat(failure.getMessage()).doesNotContain("credential-secret");
                });
    }

    private static FoundryChatClient client(
            FoundryChatClientOptions options, RecordingTransport transport, int capacity) {
        FoundryChatClientOptions.Builder builder = FoundryChatClientOptions.builder()
                .projectEndpoint(options.projectEndpoint())
                .tokenCredential(options.tokenCredential())
                .continuationMode(options.continuationMode())
                .maxBufferedUpdates(capacity);
        options.model().ifPresent(builder::model);
        options.agentName().ifPresent(builder::agentName);
        options.agentVersion().ifPresent(builder::agentVersion);
        options.defaultConversationId().ifPresent(builder::defaultConversationId);
        FoundryChatClientOptions configured = builder.build();
        return FoundryChatClient.builder()
                .options(configured)
                .transport(transport)
                .build();
    }

    private static FoundryChatClientOptions modelOptions() {
        return FoundryChatClientOptions.builder()
                .projectEndpoint(endpoint())
                .model("deployment")
                .tokenCredential(context ->
                        Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1))))
                .build();
    }

    private static FoundryChatClientOptions agentOptions() {
        return FoundryChatClientOptions.builder()
                .projectEndpoint(endpoint())
                .agentName("weather-agent")
                .agentVersion("2")
                .tokenCredential(context ->
                        Mono.just(new AccessToken("token", OffsetDateTime.now().plusHours(1))))
                .build();
    }

    private static String endpoint() {
        return "https://resource.services.ai.azure.com/api/projects/project-one";
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty());
    }

    private static OpenAITransport.Response response() {
        return new OpenAITransport.Response(
                "response-1",
                "conversation-1",
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

    private static final class RecordingTransport implements FoundryTransport {
        private OpenAITransport.Request request;

        private RuntimeException failure;

        private boolean pending;

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
                            "conversation-1",
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
    }
}
