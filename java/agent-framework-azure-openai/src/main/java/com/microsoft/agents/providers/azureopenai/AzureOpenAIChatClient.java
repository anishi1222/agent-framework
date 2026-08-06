// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
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
 * Implements provider-neutral chat through the Azure OpenAI Responses API.
 *
 * <p>The client reuses the tested OpenAI Responses lifecycle and protocol mapper while Azure
 * endpoint, API-version, authentication, SDK, errors, and metadata remain isolated in this module.
 * It is safe for concurrent requests. Streaming is cold, bounded, single-subscriber, and
 * cancellation-propagating.
 *
 * <p>A {@code ChatOptions.conversationId} beginning with {@code conv_} denotes a Responses
 * conversation and is rejected before transport because the pinned Azure SDK/API version cannot
 * represent it. Any other non-blank value is mapped to {@code previous_response_id}.
 */
public final class AzureOpenAIChatClient implements ChatClient {
    private final AzureOpenAIChatClientOptions options;

    private final OpenAIChatClient delegate;

    private final AtomicBoolean closed = new AtomicBoolean();

    private AzureOpenAIChatClient(
            AzureOpenAIChatClientOptions options, AzureOpenAITransport transport, boolean ownsTransport) {
        this.options = options;
        OpenAIChatClientOptions delegateOptions = OpenAIChatClientOptions.builder()
                .model(options.deployment())
                .timeout(options.timeout())
                .maxRetries(options.maxRetries())
                .maxBufferedUpdates(options.maxBufferedUpdates())
                .responseOptions(OpenAIResponseOptions.builder()
                        .includeEncryptedReasoning(false)
                        .build())
                .build();
        delegate = OpenAIChatClient.builder()
                .options(delegateOptions)
                .transport(transport, ownsTransport)
                .build();
    }

    /**
     * Creates an Azure OpenAI client builder.
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
    public AzureOpenAIChatClientOptions options() {
        return options;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        CompletionStage<ChatResponse> stage;
        try {
            AzureOpenAIRequestValidation.validate(request);
            stage = delegate.completeAsync(request, cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(AzureOpenAIErrorMapper.map(failure));
        }
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        stage.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(AzureOpenAIErrorMapper.map(failure));
            } else if (response == null) {
                result.completeExceptionally(
                        AzureOpenAIErrorMapper.map(new IllegalStateException("Azure OpenAI returned no response.")));
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
            AzureOpenAIRequestValidation.validate(request);
            source = delegate.completeStreaming(request, cancellation);
        } catch (RuntimeException failure) {
            return failedPublisher(AzureOpenAIErrorMapper.map(failure));
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
                downstream.onError(AzureOpenAIErrorMapper.map(failure));
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
     * transferred through {@link Builder#transport(AzureOpenAITransport, boolean)}.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close();
        }
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
        source.forEach((key, value) -> result.put(
                key.startsWith("openai.") ? "azureOpenai." + key.substring("openai.".length()) : key, value));
        result.putIfAbsent("azureOpenai.deployment", StateValue.string(options.deployment()));
        result.putIfAbsent("azureOpenai.apiVersion", StateValue.string(options.apiVersion()));
        return Map.copyOf(result);
    }

    private static StateValue remapContinuation(StateValue continuation) {
        if (!(continuation instanceof StateValue.ObjectValue object)) {
            return continuation;
        }
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>(object.values());
        if (StateValue.string("openai").equals(values.get("provider"))) {
            values.put("provider", StateValue.string("azure-openai"));
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

    /** Builds immutable {@link AzureOpenAIChatClient} instances. */
    public static final class Builder {
        private AzureOpenAIChatClientOptions options;

        private AzureOpenAITransport transport;

        private boolean closeTransport;

        private Builder() {}

        /**
         * Sets required immutable client options.
         *
         * @param options client options
         * @return this builder
         */
        public Builder options(AzureOpenAIChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Injects a caller-owned deterministic transport boundary.
         *
         * @param transport transport boundary
         * @return this builder
         */
        public Builder transport(AzureOpenAITransport transport) {
            return transport(transport, false);
        }

        /**
         * Injects a transport and selects whether ownership transfers to the client.
         *
         * @param transport transport boundary
         * @param closeTransport whether the client closes the transport
         * @return this builder
         */
        public Builder transport(AzureOpenAITransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Creates a configured client.
         *
         * @return Azure OpenAI chat client
         */
        public AzureOpenAIChatClient build() {
            AzureOpenAIChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            if (transport == null) {
                return new AzureOpenAIChatClient(builtOptions, AzureOpenAISdkTransport.create(builtOptions), true);
            }
            return new AzureOpenAIChatClient(builtOptions, transport, closeTransport);
        }
    }
}
