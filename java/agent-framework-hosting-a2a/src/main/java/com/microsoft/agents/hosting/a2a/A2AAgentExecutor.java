// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.A2AContentConverter;
import com.microsoft.agents.protocols.a2a.A2AJsonCodec;
import com.microsoft.agents.protocols.a2a.A2ALimits;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.Part;
import com.microsoft.agents.protocols.a2a.Role;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TextPart;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Hosts a framework {@link Agent} through A2A task lifecycle semantics. */
public final class A2AAgentExecutor implements A2AExecutor {
    private final Agent<?> agent;
    private final List<String> outputModes;
    private final A2AJsonCodec codec;

    /**
     * Creates an agent executor.
     *
     * @param agent framework agent
     * @param outputModes advertised output modes
     * @param limits conversion limits
     */
    public A2AAgentExecutor(
            Agent<?> agent, List<String> outputModes, A2ALimits limits) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.outputModes = List.copyOf(outputModes);
        codec = new A2AJsonCodec(Objects.requireNonNull(limits, "limits"));
    }

    @Override
    public CompletionStage<Void> executeAsync(
            A2AExecutionContext context,
            A2AEventSink sink,
            RunCancellation cancellation) {
        Message input = A2AContentConverter.toFrameworkMessage(
                context.request().message(), List.of("*/*"), codec);
        RunOptions options = runOptions(context);
        return sink.updateStatusAsync(TaskState.TASK_STATE_WORKING, null)
                .thenCompose(ignored -> context.streaming()
                        ? executeStreaming(input, options, sink, cancellation)
                        : executeFinite(input, options, sink, cancellation))
                .exceptionallyCompose(failure ->
                        handleBoundary(unwrap(failure), sink));
    }

    private CompletionStage<Void> executeFinite(
            Message input,
            RunOptions options,
            A2AEventSink sink,
            RunCancellation cancellation) {
        return agent.runAsync(List.of(input), options, cancellation)
                .thenCompose(response -> {
                    List<Part> parts = parts(response);
                    CompletionStage<?> artifactStage = parts.isEmpty()
                            ? CompletableFuture.completedFuture(null)
                            : sink.addArtifactAsync(
                                    Artifact.builder(sink.current().id() + "-result")
                                            .name("result")
                                            .parts(parts)
                                            .build(),
                                    false,
                                    true,
                                    Map.of());
                    return artifactStage.thenCompose(ignored -> sink.updateStatusAsync(
                                    TaskState.TASK_STATE_COMPLETED, null))
                            .thenApply(ignored -> null);
                });
    }

    private CompletionStage<Void> executeStreaming(
            Message input,
            RunOptions options,
            A2AEventSink sink,
            RunCancellation cancellation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Flow.Publisher<AgentResponseUpdate> updates =
                agent.runStreaming(List.of(input), options, cancellation);
        updates.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            private AgentResponseUpdate pending;
            private int chunk;

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(AgentResponseUpdate update) {
                if (pending == null) {
                    pending = update;
                    subscription.request(1);
                    return;
                }
                AgentResponseUpdate emitted = pending;
                pending = update;
                emitUpdate(emitted, false).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        subscription.request(1);
                    } else {
                        subscription.cancel();
                        result.completeExceptionally(unwrap(failure));
                    }
                });
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                CompletionStage<?> finalArtifact = pending == null
                        ? CompletableFuture.completedFuture(null)
                        : emitUpdate(pending, true);
                finalArtifact
                        .thenCompose(ignored -> sink.updateStatusAsync(
                                TaskState.TASK_STATE_COMPLETED, null))
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(unwrap(failure));
                            }
                        });
            }

            private CompletionStage<?> emitUpdate(
                    AgentResponseUpdate update, boolean lastChunk) {
                if (update.contents().isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                Message message = new Message(
                        com.microsoft.agents.core.Role.ASSISTANT,
                        update.contents(),
                        update.authorName(),
                        update.messageId(),
                        update.metadata());
                List<Part> parts =
                        A2AContentConverter.toA2AParts(List.of(message), outputModes, codec);
                if (parts.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                int currentChunk = chunk++;
                return sink.addArtifactAsync(
                        Artifact.builder(sink.current().id() + "-result")
                                .name("result")
                                .parts(parts)
                                .build(),
                        currentChunk > 0,
                        lastChunk,
                        update.metadata());
            }
        });
        return result;
    }

    private CompletionStage<Void> handleBoundary(
            Throwable failure, A2AEventSink sink) {
        if (failure instanceof ApprovalRequiredException approval) {
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
            metadata.put(
                    "continuationId",
                    StateValue.string(approval.continuation().continuationId()));
            return sink.updateStatusAsync(
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            statusMessage(
                                    "Agent input is required to approve requested tools.",
                                    metadata))
                    .thenApply(ignored -> null);
        }
        if (failure instanceof A2AAuthRequiredException auth) {
            return sink.updateStatusAsync(
                            TaskState.TASK_STATE_AUTH_REQUIRED,
                            statusMessage(auth.getMessage(), auth.metadata()))
                    .thenApply(ignored -> null);
        }
        return CompletableFuture.failedFuture(failure);
    }

    private List<Part> parts(AgentResponse<?> response) {
        return A2AContentConverter.toA2AParts(response.messages(), outputModes, codec);
    }

    private static com.microsoft.agents.protocols.a2a.Message statusMessage(
            String text, Map<String, StateValue> metadata) {
        return com.microsoft.agents.protocols.a2a.Message.builder(Role.ROLE_AGENT)
                .parts(List.of(new TextPart(text)))
                .metadata(metadata)
                .build();
    }

    private static RunOptions runOptions(A2AExecutionContext context) {
        LinkedHashMap<String, StateValue> metadata =
                new LinkedHashMap<>(context.request().metadata());
        metadata.put(
                "a2a.returnImmediately",
                StateValue.bool(
                        context.request().configuration().returnImmediately()));
        metadata.put(
                "a2a.acceptedOutputModes",
                StateValue.array(context.request()
                        .configuration()
                        .acceptedOutputModes()
                        .stream()
                        .map(StateValue::string)
                        .toList()));
        metadata.put("a2a.taskId", StateValue.string(context.task().id()));
        metadata.put("a2a.contextId", StateValue.string(context.task().contextId()));
        return new RunOptions(null, null, metadata);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }
}
