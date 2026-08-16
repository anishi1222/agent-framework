// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.SynchronousExecutionException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides shared validation, run identity, cancellation, streaming, and lifecycle behavior.
 *
 * <p>Finite asynchronous and synchronous calls share {@link #startRun(List, RunOptions,
 * RunCancellation)}. Streaming runs use the same context and lifecycle bookkeeping while retaining
 * their provider's true update path. The default constructor owns a virtual-thread-per-task executor;
 * a caller-provided executor is never closed.
 *
 * @param <T> optional structured response value type
 */
public abstract class BaseAgent<T> implements Agent<T> {
    private static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private static final long CLOSE_TIMEOUT_SECONDS = 30;

    private final AgentMetadata metadata;

    private final Executor executor;

    private final ExecutorService ownedExecutor;

    private final AgentMiddlewarePipeline<T> middlewarePipeline;

    private final Object lifecycleLock = new Object();

    private final Set<RunHandle<?>> activeRuns = Collections.newSetFromMap(new IdentityHashMap<>());

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an agent that owns a virtual-thread-per-task executor.
     *
     * @param metadata immutable agent metadata
     */
    protected BaseAgent(AgentMetadata metadata) {
        this(metadata, Executors.newVirtualThreadPerTaskExecutor(), true, List.of());
    }

    /**
     * Creates an agent using a caller-owned executor.
     *
     * @param metadata immutable agent metadata
     * @param executor caller-owned executor, which this agent never closes
     */
    protected BaseAgent(AgentMetadata metadata, Executor executor) {
        this(metadata, executor, false, List.of());
    }

    /**
     * Creates an agent that owns a virtual-thread executor and applies agent middleware.
     *
     * @param metadata immutable agent metadata
     * @param middleware middleware in registration order
     */
    protected BaseAgent(AgentMetadata metadata, Collection<? extends AgentMiddleware<T>> middleware) {
        this(metadata, Executors.newVirtualThreadPerTaskExecutor(), true, middleware);
    }

    /**
     * Creates an agent using a caller-owned executor and agent middleware.
     *
     * @param metadata immutable agent metadata
     * @param executor caller-owned executor
     * @param middleware middleware in registration order
     */
    protected BaseAgent(
            AgentMetadata metadata, Executor executor, Collection<? extends AgentMiddleware<T>> middleware) {
        this(metadata, executor, false, middleware);
    }

    private BaseAgent(
            AgentMetadata metadata,
            Executor executor,
            boolean ownsExecutor,
            Collection<? extends AgentMiddleware<T>> middleware) {
        this.metadata = AgentValidation.requireNonNull(metadata, "metadata");
        this.executor = AgentValidation.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
        this.middlewarePipeline = new AgentMiddlewarePipeline<>(middleware);
    }

    @Override
    public final AgentMetadata metadata() {
        return metadata;
    }

    /**
     * Returns whether close has begun.
     *
     * @return {@code true} after the first close request
     */
    public final boolean isClosed() {
        return closed.get();
    }

    @Override
    public final RunHandle<AgentResponse<T>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return startRunInternal(messages, options, cancellation, null);
    }

    /**
     * Starts a finite run bound to an active session.
     *
     * @param messages ordered caller input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @param session active session
     * @return run handle
     */
    protected final RunHandle<AgentResponse<T>> startRunWithSession(
            List<Message> messages, RunOptions options, RunCancellation cancellation, AgentSession session) {
        return startRunInternal(messages, options, cancellation, AgentValidation.requireNonNull(session, "session"));
    }

    private RunHandle<AgentResponse<T>> startRunInternal(
            List<Message> messages, RunOptions options, RunCancellation cancellation, AgentSession session) {
        List<Message> safeMessages = AgentValidation.copyMessages(messages);
        RunOptions safeOptions = AgentValidation.requireNonNull(options, "options");
        RunCancellation safeCancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        FeatureUsageIndexes.markCoreAgentUsed();
        RunHandleSource<AgentResponse<T>> source = createRunSource(safeCancellation);
        AgentRunContext context = newContext(safeMessages, safeOptions, source.cancellation(), session);
        if (source.cancellation().isCancellationRequested()) {
            return source.handle();
        }

        try {
            executor.execute(() -> startFiniteExecution(context, source));
        } catch (RejectedExecutionException failure) {
            source.tryFail(new AgentExecutionException("Agent executor rejected the run.", failure));
        } catch (RuntimeException failure) {
            source.tryFail(normalizeFailure(failure, "Agent executor failed to start the run."));
        }
        return source.handle();
    }

    @Override
    public final Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runStreamingInternal(messages, options, cancellation, null, new CompletableFuture<>());
    }

    /**
     * Creates a cold streaming run bound to an active session.
     *
     * @param messages ordered caller input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @param session active session
     * @return cold single-subscriber publisher
     */
    protected final Flow.Publisher<AgentResponseUpdate> runStreamingWithSession(
            List<Message> messages, RunOptions options, RunCancellation cancellation, AgentSession session) {
        return runManagedStreamingWithSession(messages, options, cancellation, session)
                .updates();
    }

    /**
     * Creates a cold session-bound streaming run and exposes its logical cleanup completion.
     *
     * <p>The settlement stage completes only after the subclass terminal result has finished,
     * including provider completion and persistence work, even when the update subscription is
     * cancelled.
     *
     * @param messages ordered caller input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @param session active session
     * @return update publisher and logical settlement stage
     */
    protected final ManagedStreamingExecution runManagedStreamingWithSession(
            List<Message> messages, RunOptions options, RunCancellation cancellation, AgentSession session) {
        CompletableFuture<Void> settled = new CompletableFuture<>();
        Flow.Publisher<AgentResponseUpdate> updates = runStreamingInternal(
                messages, options, cancellation, AgentValidation.requireNonNull(session, "session"), settled);
        return new ManagedStreamingExecution(updates, settled.minimalCompletionStage());
    }

    private Flow.Publisher<AgentResponseUpdate> runStreamingInternal(
            List<Message> messages,
            RunOptions options,
            RunCancellation cancellation,
            AgentSession session,
            CompletableFuture<Void> settled) {
        List<Message> safeMessages = AgentValidation.copyMessages(messages);
        RunOptions safeOptions = AgentValidation.requireNonNull(options, "options");
        RunCancellation safeCancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        ensureOpen();

        AtomicReference<RunHandleSource<AgentResponse<T>>> sourceReference = new AtomicReference<>();
        AtomicReference<Flow.Subscription> upstreamReference = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> publisherReference = new AtomicReference<>();

        SingleSubscriberPublisher<AgentResponseUpdate> publisher = new SingleSubscriberPublisher<>(
                () -> {
                    FeatureUsageIndexes.markCoreAgentUsed();
                    beginStreamingRun(
                            safeMessages,
                            safeOptions,
                            safeCancellation,
                            session,
                            publisherReference.get(),
                            sourceReference,
                            upstreamReference,
                            settled);
                },
                () -> {
                    RunHandleSource<AgentResponse<T>> source = sourceReference.get();
                    if (source == null) {
                        safeCancellation.cancel();
                        settled.complete(null);
                    } else {
                        source.cancellation().cancel();
                    }
                    Flow.Subscription upstream = upstreamReference.get();
                    if (upstream != null) {
                        upstream.cancel();
                    }
                },
                DEFAULT_MAX_BUFFERED_UPDATES,
                SingleSubscriberPublisher.UpdateMode.BUFFERED,
                limit -> new AgentExecutionException(
                        "Streaming update buffer exceeded maxBufferedUpdates=" + limit + "."),
                () -> {
                    RunHandleSource<AgentResponse<T>> source = sourceReference.get();
                    if (source == null) {
                        safeCancellation.cancel();
                    } else {
                        source.cancellation().cancel();
                    }
                    Flow.Subscription upstream = upstreamReference.getAndSet(null);
                    if (upstream != null) {
                        upstream.cancel();
                    }
                });
        publisherReference.set(publisher);
        return publisher;
    }

    /**
     * Executes one finite run for an explicitly propagated context.
     *
     * @param context immutable run context
     * @return non-null terminal response stage
     */
    protected abstract CompletionStage<AgentResponse<T>> executeAsync(AgentRunContext context);

    /**
     * Creates one streaming run for an explicitly propagated context.
     *
     * @param context immutable run context
     * @return non-null update publisher and terminal result stage
     */
    protected abstract StreamingExecution<T> executeStreaming(AgentRunContext context);

    /**
     * Returns the executor selected for this agent.
     *
     * @return framework-owned or caller-owned executor
     */
    protected final Executor executor() {
        return executor;
    }

    /**
     * Releases subclass resources after active runs have received cancellation.
     *
     * <p>The default implementation owns no additional resources.
     */
    protected void closeResources() {}

    /**
     * Cancels active runs, awaits their logical terminal state, and releases owned resources.
     *
     * <p>New runs are rejected as soon as close begins. Caller-owned executors are never shut down.
     * Framework-owned executor tasks are interrupted after logical runs terminate, and close fails
     * rather than silently abandoning an executor that does not terminate within the close timeout.
     */
    @Override
    public final void close() {
        List<RunHandle<?>> runs;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            runs = List.copyOf(activeRuns);
        }

        runs.forEach(RunHandle::cancel);
        Throwable closeFailure = null;
        try {
            closeResources();
        } catch (RuntimeException failure) {
            closeFailure = normalizeFailure(failure, "Agent resource close failed.");
        }

        try {
            awaitRuns(runs);
            closeOwnedExecutor();
        } catch (RuntimeException failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else {
                closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (closeFailure != null) {
            throw new AgentExecutionException("Agent close failed.", closeFailure);
        }
    }

    private void startFiniteExecution(AgentRunContext context, RunHandleSource<AgentResponse<T>> source) {
        if (source.cancellation().isCancellationRequested()) {
            return;
        }
        CompletionStage<AgentResponse<T>> stage;
        try {
            AgentMiddlewareContext<T> middlewareContext =
                    new AgentMiddlewareContext<>(this, context, new MiddlewareMetadata(context.metadata()));
            stage = middlewarePipeline.executeAsync(middlewareContext, next -> executeAsync(next.runContext()));
        } catch (RuntimeException failure) {
            source.tryFail(normalizeFailure(failure, "Agent run failed."));
            return;
        }
        if (stage == null) {
            source.tryFail(new AgentExecutionException("Agent execution returned a null CompletionStage."));
            return;
        }
        stage.whenComplete((response, failure) -> completeSource(source, response, failure));
    }

    private void beginStreamingRun(
            List<Message> messages,
            RunOptions options,
            RunCancellation cancellation,
            AgentSession session,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            AtomicReference<RunHandleSource<AgentResponse<T>>> sourceReference,
            AtomicReference<Flow.Subscription> upstreamReference,
            CompletableFuture<Void> settled) {
        RunHandleSource<AgentResponse<T>> source = createRunSource(cancellation);
        sourceReference.set(source);
        AgentRunContext context = newContext(messages, options, source.cancellation(), session);
        if (source.cancellation().isCancellationRequested()) {
            sink.fail(new RunCancelledException());
            settled.complete(null);
            return;
        }

        try {
            executor.execute(
                    () -> startStreamingExecution(context, source, cancellation, sink, upstreamReference, settled));
        } catch (RejectedExecutionException failure) {
            AgentExecutionException rejection =
                    new AgentExecutionException("Agent executor rejected the streaming run.", failure);
            source.tryFail(rejection);
            sink.fail(rejection);
            settled.complete(null);
        } catch (RuntimeException failure) {
            Throwable normalized = normalizeFailure(failure, "Agent executor failed to start the streaming run.");
            source.tryFail(normalized);
            sink.fail(normalized);
            settled.complete(null);
        }
    }

    private void startStreamingExecution(
            AgentRunContext context,
            RunHandleSource<AgentResponse<T>> source,
            RunCancellation runCancellation,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            AtomicReference<Flow.Subscription> upstreamReference,
            CompletableFuture<Void> settled) {
        if (source.cancellation().isCancellationRequested()) {
            sink.fail(new RunCancelledException());
            settled.complete(null);
            return;
        }

        StreamingExecution<T> execution;
        try {
            AgentMiddlewareContext<T> middlewareContext =
                    new AgentMiddlewareContext<>(this, context, new MiddlewareMetadata(context.metadata()));
            AgentStreamingResult<T> middlewareResult = middlewarePipeline.executeStreaming(middlewareContext, next -> {
                StreamingExecution<T> core = executeStreaming(next.runContext());
                if (core == null) {
                    throw new AgentExecutionException("Agent streaming execution returned null.");
                }
                return new AgentStreamingResult<>(core.updates(), core.resultAsync());
            });
            execution = new StreamingExecution<>(middlewareResult.updates(), middlewareResult.resultAsync());
        } catch (RuntimeException failure) {
            Throwable normalized = normalizeFailure(failure, "Agent streaming run failed.");
            source.tryFail(normalized);
            sink.fail(normalized);
            settled.complete(null);
            return;
        }
        if (execution == null) {
            AgentExecutionException failure = new AgentExecutionException("Agent streaming execution returned null.");
            source.tryFail(failure);
            sink.fail(failure);
            settled.complete(null);
            return;
        }

        AtomicBoolean updatesCompleted = new AtomicBoolean();
        AtomicBoolean resultCompleted = new AtomicBoolean();
        AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>(() -> {});
        cancellationRegistration.set(RunCancellations.register(source.cancellation(), () -> {
            Flow.Subscription upstream = upstreamReference.get();
            if (upstream != null) {
                upstream.cancel();
            }
            sink.fail(new RunCancelledException());
        }));
        source.handle()
                .resultAsync()
                .whenComplete(
                        (ignored, failure) -> cancellationRegistration.get().close());

        execution.resultAsync().whenComplete((response, failure) -> {
            try {
                if (failure != null) {
                    Throwable normalized = normalizeFailure(failure, "Agent streaming run failed.");
                    source.tryFail(normalized);
                    sink.fail(normalized);
                    Flow.Subscription upstream = upstreamReference.get();
                    if (upstream != null) {
                        upstream.cancel();
                    }
                    return;
                }
                if (response == null) {
                    AgentExecutionException nullFailure =
                            new AgentExecutionException("Agent streaming result completed with null.");
                    source.tryFail(nullFailure);
                    sink.fail(nullFailure);
                    return;
                }
                if (source.tryComplete(response)) {
                    resultCompleted.set(true);
                    if (updatesCompleted.get()) {
                        sink.complete();
                    }
                }
            } finally {
                settled.complete(null);
            }
        });

        try {
            execution.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    if (!upstreamReference.compareAndSet(null, subscription)) {
                        subscription.cancel();
                        return;
                    }
                    if (source.cancellation().isCancellationRequested()) {
                        subscription.cancel();
                        return;
                    }
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AgentResponseUpdate item) {
                    if (source.cancellation().isCancellationRequested()) {
                        Flow.Subscription upstream = upstreamReference.get();
                        if (upstream != null) {
                            upstream.cancel();
                        }
                        return;
                    }
                    try {
                        sink.emit(AgentValidation.requireNonNull(item, "update"));
                    } catch (RuntimeException failure) {
                        source.tryFail(normalizeFailure(failure, "Agent streaming delivery failed."));
                        Flow.Subscription upstream = upstreamReference.get();
                        if (upstream != null) {
                            upstream.cancel();
                        }
                        runCancellation.cancel();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    Throwable normalized = normalizeFailure(throwable, "Agent streaming publisher failed.");
                    source.tryFail(normalized);
                    sink.fail(normalized);
                }

                @Override
                public void onComplete() {
                    updatesCompleted.set(true);
                    if (resultCompleted.get()) {
                        sink.complete();
                    }
                }
            });
        } catch (RuntimeException failure) {
            Throwable normalized = normalizeFailure(failure, "Agent streaming publisher subscription failed.");
            source.tryFail(normalized);
            sink.fail(normalized);
        }
    }

    private AgentRunContext newContext(
            List<Message> messages, RunOptions options, RunCancellation cancellation, AgentSession session) {
        return new AgentRunContext(
                UUID.randomUUID().toString(),
                metadata,
                Instant.now(),
                messages,
                options,
                cancellation,
                options.metadata(),
                session,
                ContextContribution.empty());
    }

    private RunHandleSource<AgentResponse<T>> createRunSource(RunCancellation cancellation) {
        RunHandleSource<AgentResponse<T>> source;
        synchronized (lifecycleLock) {
            ensureOpenLocked();
            source = new RunHandleSource<>(cancellation);
            activeRuns.add(source.handle());
        }
        source.handle().resultAsync().whenComplete((ignored, failure) -> {
            synchronized (lifecycleLock) {
                activeRuns.remove(source.handle());
                lifecycleLock.notifyAll();
            }
        });
        return source;
    }

    private void ensureOpen() {
        synchronized (lifecycleLock) {
            ensureOpenLocked();
        }
    }

    private void ensureOpenLocked() {
        if (closed.get()) {
            throw new AgentExecutionException("Agent is closed.");
        }
    }

    private static <T> void completeSource(RunHandleSource<T> source, T response, Throwable failure) {
        if (failure != null) {
            source.tryFail(normalizeFailure(failure, "Agent run failed."));
        } else if (response == null) {
            source.tryFail(new AgentExecutionException("Agent execution completed with null."));
        } else {
            source.tryComplete(response);
        }
    }

    private static Throwable normalizeFailure(Throwable failure, String message) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof AgentFrameworkException || cause instanceof Error) {
            return cause;
        }
        return new AgentExecutionException(message, cause);
    }

    private static void awaitRuns(List<RunHandle<?>> runs) {
        CompletableFuture<?>[] terminals = runs.stream()
                .map(RunHandle::resultAsync)
                .map(stage -> stage.handle((ignored, failure) -> null))
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(terminals).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException("Agent close was interrupted.", exception);
        } catch (TimeoutException exception) {
            throw new AgentExecutionException("Agent close timed out while awaiting active runs.", exception);
        } catch (ExecutionException exception) {
            throw new AgentExecutionException(
                    "Agent close failed while awaiting active runs.", RunHandles.unwrap(exception.getCause()));
        }
    }

    private void closeOwnedExecutor() {
        if (ownedExecutor == null) {
            return;
        }
        ownedExecutor.shutdownNow();
        try {
            if (!ownedExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AgentExecutionException("Agent-owned executor did not terminate within the close timeout.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException("Agent executor close was interrupted.", exception);
        }
    }

    /** Couples a cold update publisher with completion of its logical cleanup work. */
    protected static final class ManagedStreamingExecution {
        private final Flow.Publisher<AgentResponseUpdate> updates;

        private final CompletionStage<Void> settledAsync;

        private ManagedStreamingExecution(
                Flow.Publisher<AgentResponseUpdate> updates, CompletionStage<Void> settledAsync) {
            this.updates = AgentValidation.requireNonNull(updates, "updates");
            this.settledAsync = AgentValidation.requireNonNull(settledAsync, "settledAsync");
        }

        /** Returns the cold update publisher. */
        public Flow.Publisher<AgentResponseUpdate> updates() {
            return updates;
        }

        /** Returns completion after logical cleanup, including cancellation cleanup. */
        public CompletionStage<Void> settledAsync() {
            return settledAsync;
        }
    }

    /**
     * Couples a true streaming update publisher with its terminal response stage.
     *
     * @param <T> optional structured response value type
     */
    protected static final class StreamingExecution<T> {
        private final Flow.Publisher<AgentResponseUpdate> updates;

        private final CompletionStage<AgentResponse<T>> resultAsync;

        /**
         * Creates a streaming execution.
         *
         * @param updates non-null update publisher
         * @param resultAsync non-null terminal result stage
         */
        public StreamingExecution(
                Flow.Publisher<AgentResponseUpdate> updates, CompletionStage<AgentResponse<T>> resultAsync) {
            this.updates = AgentValidation.requireNonNull(updates, "updates");
            this.resultAsync = AgentValidation.requireNonNull(resultAsync, "resultAsync");
        }

        private Flow.Publisher<AgentResponseUpdate> updates() {
            return updates;
        }

        private CompletionStage<AgentResponse<T>> resultAsync() {
            return resultAsync;
        }
    }
}
