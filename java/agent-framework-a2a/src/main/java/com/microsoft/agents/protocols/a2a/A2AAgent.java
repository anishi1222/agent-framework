// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * Adapts a remote A2A v1 endpoint to framework {@link Agent} semantics.
 *
 * <p>The ordinary Agent response carries a framework-owned {@link A2AContinuation} in its
 * continuation token whenever a remote task exists. {@link #startA2ARun(List, RunOptions,
 * RunCancellation)} exposes explicit working, input-required, and auth-required outcomes. Remote
 * failed, canceled, rejected, malformed, or protocol-error tasks complete exceptionally and are
 * never represented as successful responses.
 */
public final class A2AAgent implements Agent<Void> {
    private final A2AClient client;
    private final A2AAgentOptions options;
    private final A2AJsonCodec codec;

    /**
     * Creates a remote-agent adapter.
     *
     * @param client A2A client
     * @param options adapter options
     */
    public A2AAgent(A2AClient client, A2AAgentOptions options) {
        this.client = Objects.requireNonNull(client, "client");
        this.options = Objects.requireNonNull(options, "options");
        codec = new A2AJsonCodec(options.limits());
    }

    @Override
    public AgentMetadata metadata() {
        return options.metadata();
    }

    @Override
    public RunHandle<AgentResponse<Void>> startRun(
            List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        RunHandle<A2AAgentResult> handle = startA2ARun(messages, runOptions, cancellation);
        return new RunHandle<>() {
            @Override
            public CompletionStage<AgentResponse<Void>> resultAsync() {
                return handle.resultAsync().thenApply(A2AAgentResult::response);
            }

            @Override
            public RunCancellation cancellation() {
                return handle.cancellation();
            }
        };
    }

    /**
     * Starts an explicitly typed remote A2A run.
     *
     * @param messages ordered framework input
     * @param runOptions run options, optionally carrying an A2A continuation
     * @param cancellation caller-owned cancellation
     * @return typed run handle
     */
    public RunHandle<A2AAgentResult> startA2ARun(
            List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(runOptions, "runOptions");
        Objects.requireNonNull(cancellation, "cancellation");
        A2AContinuation continuation = continuation(runOptions);
        if (continuation != null
                && (continuation.state() == TaskState.TASK_STATE_SUBMITTED
                        || continuation.state() == TaskState.TASK_STATE_WORKING)) {
            if (!messages.isEmpty()) {
                return failed(
                        cancellation,
                        new A2AConversionException(
                                "A working A2A task cannot accept a new message; call resumeAsync or supply an empty message list."));
            }
            RunHandle<Task> remote = client.startGetTask(new A2ARequests.GetTask(continuation.taskId()));
            return mapRemote(remote, cancellation, this::mapTask);
        }

        com.microsoft.agents.protocols.a2a.Message message =
                A2AContentConverter.toA2AMessage(messages, continuation, options.inputModes(), codec);
        SendMessageRequest request = new SendMessageRequest(
                message,
                new SendMessageConfiguration(options.outputModes(), 0, null, false),
                requestMetadata(runOptions),
                null);
        return mapRemote(client.startSendMessage(request), cancellation, this::mapResult);
    }

    /**
     * Runs with an explicit typed outcome.
     *
     * @param messages ordered framework input
     * @param runOptions run options
     * @param cancellation caller-owned cancellation
     * @return result stage
     */
    public CompletionStage<A2AAgentResult> runA2AAsync(
            List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        return startA2ARun(messages, runOptions, cancellation).resultAsync();
    }

    /**
     * Polls an existing remote task without sending another user message.
     *
     * @param continuation task continuation
     * @param cancellation caller-owned cancellation
     * @return explicit task outcome
     */
    public CompletionStage<A2AAgentResult> resumeAsync(A2AContinuation continuation, RunCancellation cancellation) {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(cancellation, "cancellation");
        return mapRemote(
                        client.startGetTask(new A2ARequests.GetTask(continuation.taskId())),
                        cancellation,
                        this::mapTask)
                .resultAsync();
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(runOptions, "runOptions");
        Objects.requireNonNull(cancellation, "cancellation");
        A2AContinuation continuation = continuation(runOptions);
        Flow.Publisher<A2AStreamEvent> events;
        if (continuation != null
                && (continuation.state() == TaskState.TASK_STATE_SUBMITTED
                        || continuation.state() == TaskState.TASK_STATE_WORKING)) {
            if (!messages.isEmpty()) {
                return failedPublisher(new A2AConversionException(
                        "A working A2A task cannot accept a new message; subscribe with an empty message list."));
            }
            events = client.subscribeToTaskStreaming(
                    new A2ARequests.SubscribeToTask(continuation.taskId()), cancellation);
        } else {
            com.microsoft.agents.protocols.a2a.Message message =
                    A2AContentConverter.toA2AMessage(messages, continuation, options.inputModes(), codec);
            events = client.sendMessageStreaming(
                    new SendMessageRequest(
                            message,
                            new SendMessageConfiguration(options.outputModes(), 0, null, false),
                            requestMetadata(runOptions),
                            null),
                    cancellation);
        }
        return new A2AAgentStreamPublisher(events, options, codec);
    }

    @Override
    public void close() {
        if (options.closeClient()) {
            client.close();
        }
    }

    private A2AAgentResult mapResult(SendMessageResult result) {
        if (result instanceof com.microsoft.agents.protocols.a2a.Message message) {
            Message mapped = A2AContentConverter.toFrameworkMessage(message, options.outputModes(), codec);
            AgentResponse<Void> response = AgentResponse.<Void>builder()
                    .messages(List.of(mapped))
                    .responseId(message.messageId())
                    .agentId(options.metadata().id())
                    .finishReason(FinishReason.STOP)
                    .metadata(message.metadata())
                    .build();
            return new A2AAgentResult(A2AAgentOutcome.COMPLETED, response, null, null);
        }
        return mapTask((Task) result);
    }

    private A2AAgentResult mapTask(Task task) {
        TaskState state = task.status().state();
        if (state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED
                || state == TaskState.TASK_STATE_REJECTED) {
            throw new A2ARemoteTaskException(task);
        }
        if (state == TaskState.TASK_STATE_UNSPECIFIED) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_AGENT_RESPONSE, "Remote A2A task returned TASK_STATE_UNSPECIFIED.");
        }
        A2AContinuation continuation = new A2AContinuation(task.id(), task.contextId(), state);
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(task.metadata());
        metadata.put("a2a.taskId", StateValue.string(task.id()));
        metadata.put("a2a.contextId", StateValue.string(task.contextId()));
        metadata.put("a2a.taskState", StateValue.string(state.name()));
        AgentResponse.Builder<Void> response = AgentResponse.<Void>builder()
                .messages(A2AContentConverter.toFrameworkMessages(task, options.outputModes(), codec))
                .responseId(task.id())
                .agentId(options.metadata().id())
                .createdAt(task.status().timestamp())
                .continuationToken(continuation.toStateValue())
                .metadata(metadata);
        A2AAgentOutcome outcome =
                switch (state) {
                    case TASK_STATE_COMPLETED -> A2AAgentOutcome.COMPLETED;
                    case TASK_STATE_INPUT_REQUIRED -> A2AAgentOutcome.INPUT_REQUIRED;
                    case TASK_STATE_AUTH_REQUIRED -> A2AAgentOutcome.AUTH_REQUIRED;
                    case TASK_STATE_SUBMITTED, TASK_STATE_WORKING -> A2AAgentOutcome.WORKING;
                    case TASK_STATE_FAILED, TASK_STATE_CANCELED, TASK_STATE_REJECTED, TASK_STATE_UNSPECIFIED ->
                        throw new IllegalStateException("Terminal failure was not rejected.");
                };
        if (outcome == A2AAgentOutcome.COMPLETED) {
            response.finishReason(FinishReason.STOP);
        }
        return new A2AAgentResult(outcome, response.build(), continuation, task);
    }

    private <S> RunHandle<A2AAgentResult> mapRemote(
            RunHandle<S> remote, RunCancellation cancellation, Function<S, A2AAgentResult> mapper) {
        RunHandleSource<A2AAgentResult> source = new RunHandleSource<>(cancellation);
        RunCancellationRegistration registration = RunCancellations.register(source.cancellation(), remote::cancel);
        remote.resultAsync().whenComplete((value, failure) -> {
            try {
                if (failure == null) {
                    source.tryComplete(mapper.apply(value));
                } else {
                    source.tryFail(unwrap(failure));
                }
            } catch (Throwable mappingFailure) {
                source.tryFail(mappingFailure);
            } finally {
                registration.close();
            }
        });
        return source.handle();
    }

    private static RunHandle<A2AAgentResult> failed(RunCancellation cancellation, Throwable failure) {
        RunHandleSource<A2AAgentResult> source = new RunHandleSource<>(cancellation);
        source.tryFail(failure);
        return source.handle();
    }

    private static Flow.Publisher<AgentResponseUpdate> failedPublisher(Throwable failure) {
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onError(failure);
                }

                @Override
                public void cancel() {}
            });
        };
    }

    private static A2AContinuation continuation(RunOptions options) {
        StateValue value = options.metadata().get(A2AAgentOptions.CONTINUATION_METADATA_KEY);
        return value == null ? null : A2AContinuation.fromStateValue(value);
    }

    private static Map<String, StateValue> requestMetadata(RunOptions options) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(options.metadata());
        metadata.remove(A2AAgentOptions.CONTINUATION_METADATA_KEY);
        return Map.copyOf(metadata);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}
