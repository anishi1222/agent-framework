// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts a structured-output planning agent into an approval-gated CodeAct agent.
 *
 * <p>The planner must return a {@link CodeActProgram} as its structured response value. This facade
 * then delegates execution to a caller-owned {@link CodeActExecutor}; it does not parse free-form
 * model text or provide an unrestricted fallback. Finite execution is canonical. Streaming emits
 * one terminal update after planning and bounded execution, while detailed lifecycle events remain
 * available through {@link CodeActOptions#eventListeners()}.
 */
public final class CodeActAgent implements Agent<CodeActResult> {
    private final AgentMetadata metadata;
    private final Agent<CodeActProgram> planner;
    private final CodeActExecutor executor;

    /**
     * Creates a non-owning facade with metadata derived from the planner.
     *
     * @param planner caller-owned structured-output planning agent
     * @param executor caller-owned bounded CodeAct executor
     */
    public CodeActAgent(Agent<CodeActProgram> planner, CodeActExecutor executor) {
        this(defaultMetadata(planner), planner, executor);
    }

    /**
     * Creates a non-owning facade with explicit metadata.
     *
     * @param metadata immutable facade metadata
     * @param planner caller-owned structured-output planning agent
     * @param executor caller-owned bounded CodeAct executor
     */
    public CodeActAgent(AgentMetadata metadata, Agent<CodeActProgram> planner, CodeActExecutor executor) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** {@inheritDoc} */
    @Override
    public AgentMetadata metadata() {
        return metadata;
    }

    /** {@inheritDoc} */
    @Override
    public RunHandle<AgentResponse<CodeActResult>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        List<Message> safeMessages = copyMessages(messages);
        RunOptions safeOptions = Objects.requireNonNull(options, "options");
        RunHandleSource<AgentResponse<CodeActResult>> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));

        final RunHandle<AgentResponse<CodeActProgram>> plannerRun;
        try {
            plannerRun = planner.startRun(safeMessages, safeOptions, source.cancellation());
        } catch (RuntimeException failure) {
            source.tryFail(failure);
            return source.handle();
        }

        plannerRun.resultAsync().whenComplete((planned, plannerFailure) -> {
            if (plannerFailure != null) {
                source.tryFail(RunHandles.unwrap(plannerFailure));
                return;
            }
            if (source.isTerminal()) {
                return;
            }
            CodeActProgram program = planned.value();
            if (program == null) {
                source.tryFail(new CodeActExecutionException(
                        "The CodeAct planning agent returned no structured CodeActProgram value."));
                return;
            }
            RunHandle<CodeActResult> execution;
            try {
                execution = executor.startRun(program, source.cancellation());
            } catch (RuntimeException failure) {
                source.tryFail(failure);
                return;
            }
            execution.resultAsync().whenComplete((result, executionFailure) -> {
                if (executionFailure != null) {
                    source.tryFail(RunHandles.unwrap(executionFailure));
                    return;
                }
                source.tryComplete(response(result));
            });
        });
        return source.handle();
    }

    /** {@inheritDoc} */
    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return new TerminalPublisher(
                copyMessages(messages),
                Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Leaves caller-owned planner and executor resources open.
     *
     * <p>Close those dependencies explicitly when their owning scope ends.
     */
    @Override
    public void close() {}

    private AgentResponse<CodeActResult> response(CodeActResult result) {
        return AgentResponse.<CodeActResult>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, result.transcript())))
                .responseId(result.runId())
                .agentId(metadata.id())
                .finishReason(finishReason(result.status()))
                .value(result)
                .metadata(resultMetadata(result))
                .build();
    }

    private AgentResponseUpdate update(AgentResponse<CodeActResult> response) {
        CodeActResult result = response.value();
        return AgentResponseUpdate.builder()
                .sequence(0)
                .contents(List.of(new TextContent(result.transcript())))
                .role(Role.ASSISTANT)
                .agentId(metadata.id())
                .responseId(result.runId())
                .finishReason(finishReason(result.status()))
                .metadata(resultMetadata(result))
                .build();
    }

    private static Map<String, StateValue> resultMetadata(CodeActResult result) {
        return Map.of(
                "codeActStatus", StateValue.string(result.status().name()),
                "programDigest", StateValue.string(result.state().programDigest()));
    }

    private static FinishReason finishReason(CodeActStatus status) {
        return switch (status) {
            case TIMED_OUT, MAX_STEPS_REACHED -> FinishReason.LENGTH;
            default -> FinishReason.STOP;
        };
    }

    private static AgentMetadata defaultMetadata(Agent<CodeActProgram> planner) {
        Agent<CodeActProgram> safePlanner = Objects.requireNonNull(planner, "planner");
        String name = safePlanner.name() == null ? "CodeAct" : safePlanner.name() + " CodeAct";
        return new AgentMetadata(
                safePlanner.id() + ":codeact",
                name,
                "Plans and executes explicitly approved bounded CodeAct programs.");
    }

    private static List<Message> copyMessages(List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("messages contains null.");
        }
        return List.copyOf(messages);
    }

    private final class TerminalPublisher implements Flow.Publisher<AgentResponseUpdate> {
        private final List<Message> messages;
        private final RunOptions options;
        private final RunCancellation cancellation;
        private final AtomicBoolean subscribed = new AtomicBoolean();

        private TerminalPublisher(List<Message> messages, RunOptions options, RunCancellation cancellation) {
            this.messages = messages;
            this.options = options;
            this.cancellation = cancellation;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onError(new IllegalStateException("CodeAct streaming publishers support one subscriber."));
                return;
            }
            subscriber.onSubscribe(new TerminalSubscription(subscriber));
        }

        private final class TerminalSubscription implements Flow.Subscription {
            private final Flow.Subscriber<? super AgentResponseUpdate> subscriber;
            private final AtomicBoolean started = new AtomicBoolean();
            private final AtomicBoolean terminated = new AtomicBoolean();
            private final AtomicReference<RunHandle<AgentResponse<CodeActResult>>> handle = new AtomicReference<>();

            private TerminalSubscription(Flow.Subscriber<? super AgentResponseUpdate> subscriber) {
                this.subscriber = subscriber;
            }

            @Override
            public void request(long count) {
                if (count <= 0) {
                    if (terminated.compareAndSet(false, true)) {
                        subscriber.onError(new IllegalArgumentException("request count must be positive."));
                    }
                    return;
                }
                if (!started.compareAndSet(false, true) || terminated.get()) {
                    return;
                }
                RunHandle<AgentResponse<CodeActResult>> run =
                        CodeActAgent.this.startRun(messages, options, cancellation);
                handle.set(run);
                if (terminated.get()) {
                    run.cancel();
                    return;
                }
                run.resultAsync().whenComplete((response, failure) -> {
                    if (!terminated.compareAndSet(false, true)) {
                        return;
                    }
                    if (failure != null) {
                        subscriber.onError(RunHandles.unwrap(failure));
                        return;
                    }
                    subscriber.onNext(update(response));
                    subscriber.onComplete();
                });
            }

            @Override
            public void cancel() {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                RunHandle<AgentResponse<CodeActResult>> run = handle.get();
                if (run != null) {
                    run.cancel();
                } else {
                    cancellation.cancel();
                }
            }
        }
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
