// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.tools.ToolMode;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAIConformanceTest {
    @Test
    void jcfProviders001_shouldBindToProductionClientAndMappingPaths() {
        // Arrange
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-PROVIDERS-001");
        ContractTransport transport = new ContractTransport();
        OpenAIChatClient client = OpenAIChatClient.builder()
                .options(OpenAIChatClientOptions.builder().model("model-1").build())
                .transport(transport)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.empty(),
                List.of(OpenAIRequestMapperTest.functionTool()),
                ToolMode.AUTO,
                null);

        // Act
        List<ChatResponseUpdate> updates = collect(client.completeStreaming(request));
        ChatResponse aggregated = ResponseAggregator.aggregateChat(updates);
        List<String> requestRoles = transport.request.input().stream()
                .filter(OpenAITransport.MessageInput.class::isInstance)
                .map(OpenAITransport.MessageInput.class::cast)
                .map(message -> message.role().name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        List<String> toolNames = transport.request.tools().stream()
                .map(OpenAITransport.FunctionTool::name)
                .toList();
        boolean typedErrorsPreserved = typedErrorPreserved(client, transport);
        boolean cancellationPropagated = cancellationPropagated(client, transport, request);
        boolean providerTypesInSharedApi = sharedApiLeaksOpenAiSdk();

        // Assert
        assertThat(fixture.caseId()).isEqualTo("JCF-PROVIDERS-001");
        assertThat(requestRoles).isEqualTo(strings(fixture.expected(), "requestRoleOrder"));
        assertThat(toolNames).isEqualTo(strings(fixture.expected(), "toolNames"));
        assertThat(!aggregated.text().isBlank()).isEqualTo(bool(fixture.expected(), "streamingUpdatesAggregate"));
        assertThat(typedErrorsPreserved).isEqualTo(bool(fixture.expected(), "typedErrorsPreserved"));
        assertThat(cancellationPropagated).isEqualTo(bool(fixture.expected(), "cancellationPropagated"));
        assertThat(providerTypesInSharedApi).isEqualTo(bool(fixture.expected(), "providerTypesInSharedApi"));
    }

    private static boolean typedErrorPreserved(OpenAIChatClient client, ContractTransport transport) {
        transport.failure = new OpenAIHttpException(503, "req-conformance", "unavailable");
        assertThatThrownBy(() -> client.completeAsync(
                                new ChatClientRequest(List.of(Message.text(Role.USER, "failure")), ChatOptions.empty()))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OpenAIHttpException.class);
        transport.failure = null;
        return true;
    }

    private static boolean cancellationPropagated(
            OpenAIChatClient client, ContractTransport transport, ChatClientRequest request) {
        transport.pending = true;
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        client.completeStreaming(request, cancellation).subscribe(new Flow.Subscriber<>() {
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
        transport.pending = false;
        return cancellation.isCancellationRequested() && transport.upstreamCancelled.get();
    }

    private static boolean sharedApiLeaksOpenAiSdk() {
        for (Class<?> type : List.of(
                ChatClient.class,
                ChatClientRequest.class,
                com.microsoft.agents.core.ChatOptions.class,
                com.microsoft.agents.core.ChatResponse.class,
                com.microsoft.agents.core.ChatResponseUpdate.class)) {
            for (var method : type.getMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && method.toGenericString().contains("com.openai.")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<ChatResponseUpdate> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        CompletableFuture<Void> completion = new CompletableFuture<>();
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
                completion.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completion.complete(null);
            }
        });
        completion.join();
        return List.copyOf(updates);
    }

    private static boolean bool(ConformanceValue.ObjectValue expected, String name) {
        return ((ConformanceValue.BooleanValue) expected.require(name)).value();
    }

    private static List<String> strings(ConformanceValue.ObjectValue expected, String name) {
        return ((ConformanceValue.ArrayValue) expected.require(name))
                .values().stream()
                        .map(ConformanceValue.StringValue.class::cast)
                        .map(ConformanceValue.StringValue::value)
                        .toList();
    }

    private static final class ContractTransport implements OpenAITransport {
        private OpenAITransport.Request request;

        private RuntimeException failure;

        private boolean pending;

        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();

        @Override
        public CompletableFuture<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            this.request = request;
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(response());
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
                private final AtomicBoolean emitted = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (!emitted.compareAndSet(false, true)) {
                        return;
                    }
                    subscriber.onNext(new OpenAITransport.ResponseStarted(
                            0,
                            "response-1",
                            null,
                            "model-1",
                            Instant.EPOCH,
                            null,
                            OpenAITransport.ResponseStatus.IN_PROGRESS));
                    subscriber.onNext(new OpenAITransport.TextDelta(1, "message-1", "hello", Map.of()));
                    subscriber.onNext(new OpenAITransport.ResponseCompleted(2, response()));
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    upstreamCancelled.set(true);
                }
            });
        }

        private static OpenAITransport.Response response() {
            return new OpenAITransport.Response(
                    "response-1",
                    null,
                    "model-1",
                    Instant.EPOCH,
                    OpenAITransport.ResponseStatus.COMPLETED,
                    List.of(new OpenAITransport.TextOutput("message-1", "hello", false, Map.of())),
                    null,
                    Map.of(),
                    null,
                    null,
                    null);
        }
    }
}
