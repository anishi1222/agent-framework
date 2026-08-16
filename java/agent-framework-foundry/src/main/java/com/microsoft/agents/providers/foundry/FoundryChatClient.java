// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAIChatClient;
import com.microsoft.agents.providers.openai.OpenAIChatClientOptions;
import com.microsoft.agents.providers.openai.OpenAIResponseOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements provider-neutral chat through Microsoft Foundry project Responses.
 *
 * <p>The client supports direct model-deployment calls and existing versioned agent references.
 * Existing-agent calls omit request-owned model, instruction, sampling, and tool declarations because
 * the server-side agent owns them. Request-owned maximum output tokens, end-user identifier,
 * persistence preference, metadata, input, and continuation are forwarded. Local {@code ChatAgent}
 * tools still execute returned function calls and send correlated results on the next turn.
 */
public final class FoundryChatClient implements ChatClient {
    private final FoundryChatClientOptions options;

    private final OpenAIChatClient delegate;

    private final AtomicBoolean closed = new AtomicBoolean();

    private FoundryChatClient(FoundryChatClientOptions options, FoundryTransport transport, boolean ownsTransport) {
        this.options = options;
        OpenAIChatClientOptions delegateOptions = OpenAIChatClientOptions.builder()
                .model(options.transportModel())
                .timeout(options.timeout())
                .maxRetries(options.maxRetries())
                .maxBufferedUpdates(options.maxBufferedUpdates())
                .responseOptions(OpenAIResponseOptions.builder()
                        .includeEncryptedReasoning(false)
                        .build())
                .build();
        FoundryMappedTransport mappedTransport = new FoundryMappedTransport(options, transport);
        delegate = OpenAIChatClient.builder()
                .options(delegateOptions)
                .transport(mappedTransport, ownsTransport)
                .build();
    }

    /**
     * Creates a Foundry client builder.
     *
     * @return client builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable client options.
     *
     * @return client options
     */
    public FoundryChatClientOptions options() {
        return options;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        CompletionStage<ChatResponse> stage;
        try {
            stage = delegate.completeAsync(withDefaultConversation(request), cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(FoundryErrorMapper.map(failure));
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        stage.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(FoundryErrorMapper.map(failure));
            } else if (response == null) {
                result.completeExceptionally(
                        FoundryErrorMapper.map(new IllegalStateException("Foundry returned no response.")));
            } else {
                result.complete(remap(response));
            }
        });
        return result;
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Flow.Publisher<ChatResponseUpdate> source;
        try {
            source = delegate.completeStreaming(withDefaultConversation(request), cancellation);
        } catch (RuntimeException failure) {
            return failedPublisher(FoundryErrorMapper.map(failure));
        }
        return downstream -> source.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                downstream.onSubscribe(subscription);
            }

            @Override
            public void onNext(ChatResponseUpdate update) {
                downstream.onNext(remap(update));
            }

            @Override
            public void onError(Throwable failure) {
                downstream.onError(FoundryErrorMapper.map(failure));
            }

            @Override
            public void onComplete() {
                downstream.onComplete();
            }
        });
    }

    /**
     * Cancels active work and closes an owned transport.
     *
     * <p>A caller-injected transport remains caller-owned unless ownership was explicitly
     * transferred through {@link Builder#transport(FoundryTransport, boolean)}. The production
     * Foundry transport owns and closes the {@code OpenAIClientAsync} created by the Azure builder,
     * including active streaming resources.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close();
        }
    }

    private ChatClientRequest withDefaultConversation(ChatClientRequest request) {
        if (request.options().conversationId() != null
                || options.defaultConversationId().isEmpty()) {
            return request;
        }
        ChatOptions source = request.options();
        ChatOptions mapped = new ChatOptions(
                source.model(),
                source.temperature(),
                source.topP(),
                source.maxTokens(),
                source.stop(),
                source.seed(),
                source.frequencyPenalty(),
                source.presencePenalty(),
                source.toolChoice(),
                source.allowMultipleToolCalls(),
                source.user(),
                source.store(),
                options.defaultConversationId().orElseThrow(),
                source.instructions(),
                source.structuredOutput(),
                source.metadata());
        return new ChatClientRequest(
                request.messages(), mapped, request.tools(), request.toolMode(), request.runContext());
    }

    private ChatResponse remap(ChatResponse response) {
        return new ChatResponse(
                response.messages(),
                response.responseId(),
                response.conversationId(),
                response.model(),
                response.createdAt(),
                response.finishReason(),
                response.usage(),
                remapContinuation(response.continuationToken()),
                remapMetadata(response.metadata()),
                response.updateSequences());
    }

    private ChatResponseUpdate remap(ChatResponseUpdate update) {
        return new ChatResponseUpdate(
                update.sequence(),
                update.contents(),
                update.role(),
                update.authorName(),
                update.responseId(),
                update.messageId(),
                update.conversationId(),
                update.model(),
                update.createdAt(),
                update.finishReason(),
                update.usage(),
                remapContinuation(update.continuationToken()),
                remapMetadata(update.metadata()));
    }

    private Map<String, StateValue> remapMetadata(Map<String, StateValue> source) {
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        source.forEach((key, value) ->
                result.put(key.startsWith("openai.") ? "foundry." + key.substring("openai.".length()) : key, value));
        result.putIfAbsent(
                "foundry.surface", StateValue.string(options.surface().name().toLowerCase(java.util.Locale.ROOT)));
        options.agentName()
                .ifPresent(agentName -> result.putIfAbsent("foundry.agentName", StateValue.string(agentName)));
        return Map.copyOf(result);
    }

    private static StateValue remapContinuation(StateValue continuation) {
        if (!(continuation instanceof StateValue.ObjectValue object)) {
            return continuation;
        }
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>(object.values());
        if (StateValue.string("openai").equals(values.get("provider"))) {
            values.put("provider", StateValue.string("foundry"));
        }
        return StateValue.object(values);
    }

    private static Flow.Publisher<ChatResponseUpdate> failedPublisher(RuntimeException failure) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean terminated;

            @Override
            public void request(long count) {
                if (!terminated) {
                    terminated = true;
                    subscriber.onError(failure);
                }
            }

            @Override
            public void cancel() {
                terminated = true;
            }
        });
    }

    /** Builds immutable {@link FoundryChatClient} instances. */
    public static final class Builder {
        private FoundryChatClientOptions options;

        private FoundryTransport transport;

        private boolean closeTransport;

        private Builder() {}

        /**
         * Sets required immutable client options.
         *
         * @param options client options
         * @return this builder
         */
        public Builder options(FoundryChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Injects a caller-owned deterministic transport boundary.
         *
         * @param transport transport boundary
         * @return this builder
         */
        public Builder transport(FoundryTransport transport) {
            return transport(transport, false);
        }

        /**
         * Injects a transport and selects whether ownership transfers to the client.
         *
         * @param transport transport boundary
         * @param closeTransport whether the client closes the transport
         * @return this builder
         */
        public Builder transport(FoundryTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Creates a configured client.
         *
         * @return Foundry chat client
         */
        public FoundryChatClient build() {
            FoundryChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport == null) {
                return new FoundryChatClient(builtOptions, FoundrySdkTransport.create(builtOptions), true);
            }
            return new FoundryChatClient(builtOptions, transport, closeTransport);
        }
    }
}
