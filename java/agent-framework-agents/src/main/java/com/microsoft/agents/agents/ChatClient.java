// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the provider-neutral chat completion service-provider interface.
 *
 * <p>Provider adapters map their SDK models at this boundary and expose only framework-owned request,
 * response, option, update, and cancellation types. Implementations must return a non-null stage or
 * publisher. A streaming publisher must propagate {@link Flow.Subscription#cancel()} to the provider
 * subscription and stop signaling after cancellation.
 */
public interface ChatClient extends AutoCloseable {
    /**
     * Completes one finite request.
     *
     * @param request immutable provider-neutral request
     * @param cancellation explicit run cancellation signal
     * @return stage producing the complete response
     */
    CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation);

    /**
     * Streams one request with backpressure and cancellation.
     *
     * @param request immutable provider-neutral request
     * @param cancellation explicit run cancellation signal
     * @return cold, single-subscriber update publisher
     */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(ChatClientRequest request, RunCancellation cancellation);

    /**
     * Completes one finite request with framework-owned cancellation.
     *
     * @param request immutable request
     * @return stage producing the complete response
     */
    default CompletionStage<ChatResponse> completeAsync(ChatClientRequest request) {
        return startCompletion(request).resultAsync();
    }

    /**
     * Completes ordered messages with framework-owned cancellation.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @return stage producing the complete response
     */
    default CompletionStage<ChatResponse> completeAsync(List<Message> messages, ChatOptions options) {
        return completeAsync(new ChatClientRequest(messages, options));
    }

    /**
     * Completes ordered messages with caller-owned cancellation.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @param cancellation explicit cancellation signal
     * @return stage producing the complete response
     */
    default CompletionStage<ChatResponse> completeAsync(
            List<Message> messages, ChatOptions options, RunCancellation cancellation) {
        return completeAsync(new ChatClientRequest(messages, options), cancellation);
    }

    /**
     * Streams one request with framework-owned cancellation.
     *
     * @param request immutable request
     * @return cold, single-subscriber update publisher
     */
    default Flow.Publisher<ChatResponseUpdate> completeStreaming(ChatClientRequest request) {
        return completeStreaming(AgentValidation.requireNonNull(request, "request"), new DefaultRunCancellation());
    }

    /**
     * Streams ordered messages with framework-owned cancellation.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @return cold, single-subscriber update publisher
     */
    default Flow.Publisher<ChatResponseUpdate> completeStreaming(List<Message> messages, ChatOptions options) {
        return completeStreaming(new ChatClientRequest(messages, options));
    }

    /**
     * Streams ordered messages with caller-owned cancellation.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @param cancellation explicit cancellation signal
     * @return cold, single-subscriber update publisher
     */
    default Flow.Publisher<ChatResponseUpdate> completeStreaming(
            List<Message> messages, ChatOptions options, RunCancellation cancellation) {
        return completeStreaming(new ChatClientRequest(messages, options), cancellation);
    }

    /**
     * Completes one request synchronously through the same run handle as {@link #completeAsync}.
     *
     * @param request immutable request
     * @return complete response
     */
    default ChatResponse complete(ChatClientRequest request) {
        return RunHandles.await(startCompletion(request), "Chat completion");
    }

    /**
     * Completes ordered messages synchronously.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @return complete response
     */
    default ChatResponse complete(List<Message> messages, ChatOptions options) {
        return complete(new ChatClientRequest(messages, options));
    }

    /**
     * Starts one explicitly cancellable finite request.
     *
     * @param request immutable request
     * @return run handle
     */
    default RunHandle<ChatResponse> startCompletion(ChatClientRequest request) {
        return startCompletion(request, new DefaultRunCancellation());
    }

    /**
     * Starts one finite request with caller-owned cancellation.
     *
     * @param request immutable request
     * @param cancellation caller-owned cancellation signal
     * @return run handle
     */
    default RunHandle<ChatResponse> startCompletion(ChatClientRequest request, RunCancellation cancellation) {
        AgentValidation.requireNonNull(request, "request");
        AgentValidation.requireNonNull(cancellation, "cancellation");
        RunHandleSource<ChatResponse> source = new RunHandleSource<>(cancellation);
        if (source.cancellation().isCancellationRequested()) {
            return source.handle();
        }
        CompletionStage<ChatResponse> stage;
        try {
            stage = completeAsync(request, source.cancellation());
        } catch (RuntimeException failure) {
            source.tryFail(normalizeFailure(failure));
            return source.handle();
        }
        if (stage == null) {
            source.tryFail(new AgentExecutionException("ChatClient.completeAsync returned null."));
            return source.handle();
        }
        stage.whenComplete((response, failure) -> {
            if (failure != null) {
                source.tryFail(normalizeFailure(failure));
            } else if (response == null) {
                source.tryFail(new AgentExecutionException("ChatClient.completeAsync completed with null."));
            } else {
                source.tryComplete(response);
            }
        });
        return source.handle();
    }

    /**
     * Starts ordered messages as one explicitly cancellable finite request.
     *
     * @param messages ordered input messages
     * @param options chat options
     * @return run handle
     */
    default RunHandle<ChatResponse> startCompletion(List<Message> messages, ChatOptions options) {
        return startCompletion(new ChatClientRequest(messages, options));
    }

    /**
     * Releases resources owned by an implementation.
     *
     * <p>The provider-neutral interface owns no resources by default.
     */
    @Override
    default void close() {}

    private static Throwable normalizeFailure(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof AgentFrameworkException || cause instanceof Error) {
            return cause;
        }
        return new AgentExecutionException("Chat completion failed.", cause);
    }
}
