// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

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
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAITransport;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AzureOpenAIConformanceTest {
    @Test
    void jcfProviders001_shouldBindAzureProductionClientAndProtocolPaths() {
        // Arrange
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-PROVIDERS-001");
        ContractTransport transport = new ContractTransport();
        AzureOpenAIChatClient client = AzureOpenAIChatClient.builder()
                .options(AzureOpenAIChatClientOptions.builder()
                        .endpoint("https://resource.openai.azure.com")
                        .deployment("deployment")
                        .apiKey("unit-test-key")
                        .build())
                .transport(transport)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.empty(),
                List.of(metadata()),
                ToolMode.AUTO,
                null);

        // Act
        ChatResponse aggregated = ResponseAggregator.aggregateChat(collect(client.completeStreaming(request)));
        List<String> roles = transport.request.input().stream()
                .filter(OpenAITransport.MessageInput.class::isInstance)
                .map(OpenAITransport.MessageInput.class::cast)
                .map(message -> message.role().name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        List<String> toolNames = transport.request.tools().stream()
                .map(OpenAITransport.FunctionTool::name)
                .toList();

        // Assert
        assertThat(roles).isEqualTo(strings(fixture.expected(), "requestRoleOrder"));
        assertThat(toolNames).isEqualTo(strings(fixture.expected(), "toolNames"));
        assertThat(!aggregated.text().isBlank()).isEqualTo(bool(fixture.expected(), "streamingUpdatesAggregate"));
        assertThat(typedErrorPreserved(client, transport)).isEqualTo(bool(fixture.expected(), "typedErrorsPreserved"));
        assertThat(cancellationPropagated(client, transport, request))
                .isEqualTo(bool(fixture.expected(), "cancellationPropagated"));
        assertThat(sharedApiLeaksAzureTypes()).isEqualTo(bool(fixture.expected(), "providerTypesInSharedApi"));
    }

    private static boolean typedErrorPreserved(AzureOpenAIChatClient client, ContractTransport transport) {
        transport.failure = new AzureOpenAIProviderException(
                "Azure OpenAI request failed.",
                AzureOpenAIProviderException.Kind.TRANSPORT,
                503,
                "request-1",
                null,
                "unavailable");
        assertThatThrownBy(() -> client.completeAsync(
                                new ChatClientRequest(List.of(Message.text(Role.USER, "failure")), ChatOptions.empty()))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AzureOpenAIProviderException.class);
        transport.failure = null;
        return true;
    }

    private static boolean cancellationPropagated(
            AzureOpenAIChatClient client, ContractTransport transport, ChatClientRequest request) {
        transport.pending = true;
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        client.completeStreaming(request, cancellation).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
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

    private static boolean sharedApiLeaksAzureTypes() {
        for (Class<?> type : List.of(
                ChatClient.class,
                ChatClientRequest.class,
                com.microsoft.agents.core.ChatOptions.class,
                com.microsoft.agents.core.ChatResponse.class,
                com.microsoft.agents.core.ChatResponseUpdate.class)) {
            for (var method : type.getMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && method.toGenericString().contains("com.azure.")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ToolMetadata metadata() {
        return new ToolMetadata(
                "lookup",
                "Looks up a value.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
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

    private static final class ContractTransport implements AzureOpenAITransport {
        private OpenAITransport.Request request;

        private RuntimeException failure;

        private boolean pending;

        private final AtomicBoolean upstreamCancelled = new AtomicBoolean();

        @Override
        public CompletableFuture<OpenAITransport.Response> completeAsync(
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
    }
}
