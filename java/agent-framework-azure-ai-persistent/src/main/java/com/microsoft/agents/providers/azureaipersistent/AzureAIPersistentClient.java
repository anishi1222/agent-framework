// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides low-level persistent agent, thread, message, and run operations.
 *
 * <p>The client owns only a scheduler that it creates itself. Caller-provided authentication
 * providers and schedulers remain caller-owned. Service agents and threads are never deleted
 * automatically.
 */
public final class AzureAIPersistentClient implements AutoCloseable {
    private final AzureAIPersistentClientOptions options;
    private final PersistentTransport transport;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService ownedScheduler;
    private final Set<PollOperation> polls = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a client using the pinned Azure SDK transport.
     *
     * @param options immutable options
     */
    public AzureAIPersistentClient(AzureAIPersistentClientOptions options) {
        this(options, PersistentSdkTransport.create(options));
    }

    AzureAIPersistentClient(AzureAIPersistentClientOptions options, PersistentTransport transport) {
        this.options = Objects.requireNonNull(options, "options");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (options.scheduler() == null) {
            ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(
                    1,
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("agent-framework-persistent-poller-", 0)
                            .factory());
            created.setRemoveOnCancelPolicy(true);
            scheduler = created;
            ownedScheduler = created;
        } else {
            scheduler = options.scheduler();
            ownedScheduler = null;
        }
    }

    /** Returns immutable client options. */
    public AzureAIPersistentClientOptions options() {
        return options;
    }

    /** Creates a persistent agent. */
    public CompletionStage<PersistentAgentDefinition> createAgentAsync(
            PersistentAgentCreateRequest request, RunCancellation cancellation) {
        ensureOpen();
        return transport.createAgentAsync(
                Objects.requireNonNull(request, "request"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Creates a persistent agent with a framework-owned cancellation signal. */
    public CompletionStage<PersistentAgentDefinition> createAgentAsync(PersistentAgentCreateRequest request) {
        return createAgentAsync(request, new DefaultRunCancellation());
    }

    /** Gets a persistent agent. */
    public CompletionStage<PersistentAgentDefinition> getAgentAsync(String agentId, RunCancellation cancellation) {
        ensureOpen();
        return transport.getAgentAsync(
                nonBlank(agentId, "agentId"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Updates a persistent agent. */
    public CompletionStage<PersistentAgentDefinition> updateAgentAsync(
            String agentId, PersistentAgentCreateRequest replacement, RunCancellation cancellation) {
        ensureOpen();
        return transport.updateAgentAsync(
                nonBlank(agentId, "agentId"),
                Objects.requireNonNull(replacement, "replacement"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Deletes a caller-selected persistent agent. */
    public CompletionStage<Void> deleteAgentAsync(String agentId, RunCancellation cancellation) {
        ensureOpen();
        return transport.deleteAgentAsync(
                nonBlank(agentId, "agentId"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Lists a bounded page of persistent agents. */
    public CompletionStage<PersistentPage<PersistentAgentDefinition>> listAgentsAsync(
            int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return transport.listAgentsAsync(
                pageSize(limit),
                optionalNonBlank(after, "after"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Creates a service thread. */
    public CompletionStage<PersistentThread> createThreadAsync(
            Map<String, String> metadata, RunCancellation cancellation) {
        ensureOpen();
        return transport.createThreadAsync(
                metadata == null ? Map.of() : Map.copyOf(metadata),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Creates a service thread without metadata. */
    public CompletionStage<PersistentThread> createThreadAsync() {
        return createThreadAsync(Map.of(), new DefaultRunCancellation());
    }

    /** Gets a service thread. */
    public CompletionStage<PersistentThread> getThreadAsync(String threadId, RunCancellation cancellation) {
        ensureOpen();
        return transport.getThreadAsync(
                nonBlank(threadId, "threadId"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Deletes a caller-selected service thread. */
    public CompletionStage<Void> deleteThreadAsync(String threadId, RunCancellation cancellation) {
        ensureOpen();
        return transport.deleteThreadAsync(
                nonBlank(threadId, "threadId"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Creates a thread message with optional attachments. */
    public CompletionStage<PersistentMessage> createMessageAsync(
            String threadId,
            com.microsoft.agents.core.Role role,
            String text,
            List<PersistentAttachment> attachments,
            Map<String, String> metadata,
            RunCancellation cancellation) {
        ensureOpen();
        return transport.createMessageAsync(
                nonBlank(threadId, "threadId"),
                Objects.requireNonNull(role, "role"),
                Objects.requireNonNull(text, "text"),
                attachments == null ? List.of() : List.copyOf(attachments),
                metadata == null ? Map.of() : Map.copyOf(metadata),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Lists one bounded page of thread messages. */
    public CompletionStage<PersistentPage<PersistentMessage>> listMessagesAsync(
            String threadId, String runId, int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return transport.listMessagesAsync(
                nonBlank(threadId, "threadId"),
                optionalNonBlank(runId, "runId"),
                pageSize(limit),
                optionalNonBlank(after, "after"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Creates a run and returns its immediate state. */
    public CompletionStage<PersistentRun> createRunAsync(PersistentRunRequest request, RunCancellation cancellation) {
        ensureOpen();
        return transport.createRunAsync(
                Objects.requireNonNull(request, "request"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Gets the current run state. */
    public CompletionStage<PersistentRun> getRunAsync(String threadId, String runId, RunCancellation cancellation) {
        ensureOpen();
        return transport.getRunAsync(
                nonBlank(threadId, "threadId"),
                nonBlank(runId, "runId"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Lists one bounded page of runs. */
    public CompletionStage<PersistentPage<PersistentRun>> listRunsAsync(
            String threadId, int limit, String after, RunCancellation cancellation) {
        ensureOpen();
        return transport.listRunsAsync(
                nonBlank(threadId, "threadId"),
                pageSize(limit),
                optionalNonBlank(after, "after"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Requests service-side run cancellation. */
    public CompletionStage<PersistentRun> cancelRunAsync(String threadId, String runId, RunCancellation cancellation) {
        ensureOpen();
        return transport.cancelRunAsync(
                nonBlank(threadId, "threadId"),
                nonBlank(runId, "runId"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /** Submits caller-reviewed function outputs to a requires-action run. */
    public CompletionStage<PersistentRun> submitToolOutputsAsync(
            String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation) {
        ensureOpen();
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must not be empty.");
        }
        return transport.submitToolOutputsAsync(
                nonBlank(threadId, "threadId"),
                nonBlank(runId, "runId"),
                List.copyOf(outputs),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Submits tool outputs and awaits the next terminal or requires-action state.
     *
     * @param threadId thread identifier
     * @param runId run identifier
     * @param outputs caller-reviewed outputs
     * @param cancellation cancellation signal
     * @return terminal run stage
     */
    public CompletionStage<PersistentRun> submitToolOutputsAndAwaitAsync(
            String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation) {
        return submitToolOutputsAsync(threadId, runId, outputs, cancellation)
                .thenCompose(run -> awaitRunAsync(run.threadId(), run.id(), cancellation, true));
    }

    /**
     * Continues a run through an explicit caller decision.
     *
     * <p>Approval is an adapter-side gate around tool-output submission. Rejection cancels the
     * service run. The pinned SDK exposes no input-required continuation endpoint, so {@link
     * PersistentContinuationKind#INPUT} fails explicitly.
     *
     * @param continuation continuation request
     * @param cancellation cancellation signal
     * @return resulting run stage
     */
    public CompletionStage<PersistentRun> continueRunAsync(
            PersistentRunContinuation continuation, RunCancellation cancellation) {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(cancellation, "cancellation");
        return switch (continuation.kind()) {
            case TOOL_OUTPUTS ->
                submitToolOutputsAndAwaitAsync(
                        continuation.threadId(), continuation.runId(), continuation.toolOutputs(), cancellation);
            case APPROVAL -> {
                if (continuation.approved() == null) {
                    throw new IllegalArgumentException("APPROVAL continuation requires an approval decision.");
                }
                if (!continuation.approved()) {
                    yield cancelRunAndAwaitAsync(continuation.threadId(), continuation.runId(), cancellation);
                }
                yield submitToolOutputsAndAwaitAsync(
                        continuation.threadId(), continuation.runId(), continuation.toolOutputs(), cancellation);
            }
            case INPUT ->
                CompletableFuture.failedFuture(new AzureAIPersistentException(
                        "azure-ai-agents-persistent:1.0.0-beta.2 has no input continuation endpoint.",
                        null,
                        AzureAIPersistentException.Kind.CONFIGURATION,
                        null,
                        null,
                        "input_continuation_unsupported",
                        null));
        };
    }

    private CompletionStage<PersistentRun> cancelRunAndAwaitAsync(
            String threadId, String runId, RunCancellation cancellation) {
        return cancelRunAsync(threadId, runId, cancellation)
                .thenCompose(run -> run.status().isTerminal()
                        ? CompletableFuture.completedStage(run)
                        : awaitRunAsync(run.threadId(), run.id(), cancellation, true));
    }

    /**
     * Starts a run and polls asynchronously until completion, failure, cancellation, expiry, or
     * requires-action.
     *
     * <p>Cancellation or timeout stops local polling and requests best-effort service-side
     * cancellation because this operation created the run.
     *
     * @param request run request
     * @return explicitly cancellable run handle
     */
    public RunHandle<PersistentRun> startRun(PersistentRunRequest request) {
        return startRun(request, new DefaultRunCancellation());
    }

    /**
     * Starts a run linked to caller-owned cancellation.
     *
     * <p>Cancellation or timeout stops local polling and requests best-effort service-side
     * cancellation because this operation created the run.
     *
     * @param request run request
     * @param cancellation cancellation signal
     * @return run handle
     */
    public RunHandle<PersistentRun> startRun(PersistentRunRequest request, RunCancellation cancellation) {
        ensureOpen();
        RunHandleSource<PersistentRun> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        transport
                .createRunAsync(Objects.requireNonNull(request, "request"), source.cancellation())
                .whenComplete((run, failure) -> {
                    if (failure != null) {
                        source.tryFail(unwrap(failure));
                        return;
                    }
                    if (source.isTerminal()) {
                        if (source.cancellation().isCancellationRequested()) {
                            cancelRunBestEffort(run.threadId(), run.id());
                        }
                        return;
                    }
                    try {
                        awaitRunAsync(run.threadId(), run.id(), source.cancellation(), true)
                                .whenComplete((terminal, pollFailure) -> {
                                    if (pollFailure != null) {
                                        source.tryFail(unwrap(pollFailure));
                                    } else {
                                        source.tryComplete(terminal);
                                    }
                                });
                    } catch (RuntimeException pollHandoffFailure) {
                        cancelRunBestEffort(run.threadId(), run.id());
                        source.tryFail(pollHandoffFailure);
                    }
                });
        return source.handle();
    }

    /**
     * Observes an existing run with bounded exponential delay and jitter.
     *
     * <p>Cancellation and timeout stop local polling only. They do not request service-side
     * cancellation of a run that this client did not start.
     *
     * @param threadId owning thread
     * @param runId run identifier
     * @param cancellation cancellation signal for local polling
     * @return terminal run stage
     */
    public CompletionStage<PersistentRun> awaitRunAsync(String threadId, String runId, RunCancellation cancellation) {
        return awaitRunAsync(threadId, runId, cancellation, false);
    }

    /**
     * Observes an existing run and optionally requests service-side cancellation when local polling
     * is cancelled or times out.
     *
     * @param threadId owning thread
     * @param runId run identifier
     * @param cancellation cancellation signal for local polling
     * @param cancelRemoteOnTimeoutOrCancellation whether to request best-effort service cancellation
     * @return terminal run stage
     */
    public CompletionStage<PersistentRun> awaitRunAsync(
            String threadId, String runId, RunCancellation cancellation, boolean cancelRemoteOnTimeoutOrCancellation) {
        PollOperation poll;
        synchronized (lifecycleLock) {
            ensureOpen();
            poll = new PollOperation(
                    nonBlank(threadId, "threadId"),
                    nonBlank(runId, "runId"),
                    Objects.requireNonNull(cancellation, "cancellation"),
                    cancelRemoteOnTimeoutOrCancellation);
            polls.add(poll);
        }
        poll.result.whenComplete((ignored, failure) -> {
            polls.remove(poll);
            poll.close();
        });
        poll.pollNow();
        return poll.result.minimalCompletionStage();
    }

    /** Starts the SDK's native server-sent-event run stream. */
    public Flow.Publisher<PersistentRunEvent> createRunStreaming(
            PersistentRunRequest request, RunCancellation cancellation) {
        ensureOpen();
        return transport.createRunStreaming(
                Objects.requireNonNull(request, "request"), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Creates an Agent Framework adapter for an existing service agent.
     *
     * @param agent immutable service agent metadata
     * @return framework agent
     */
    public AzureAIPersistentAgent asAgent(PersistentAgentDefinition agent) {
        ensureOpen();
        return new AzureAIPersistentAgent(this, Objects.requireNonNull(agent, "agent"), false);
    }

    /** Cancels active pollers and releases only a framework-created scheduler. */
    @Override
    public void close() {
        List<PollOperation> activePolls;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            activePolls = List.copyOf(polls);
        }
        activePolls.forEach(PollOperation::cancel);
        if (ownedScheduler != null) {
            ownedScheduler.shutdownNow();
            try {
                if (!ownedScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new AzureAIPersistentException(
                            "Persistent polling scheduler did not terminate.",
                            null,
                            AzureAIPersistentException.Kind.TRANSPORT,
                            null,
                            null,
                            "scheduler_close_timeout",
                            null);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AzureAIPersistentException(
                        "Persistent client close was interrupted.",
                        failure,
                        AzureAIPersistentException.Kind.TRANSPORT,
                        null,
                        null,
                        "close_interrupted",
                        null);
            }
        }
    }

    private int pageSize(int value) {
        if (value <= 0 || value > options.maxPageSize()) {
            throw new IllegalArgumentException("limit must be between 1 and " + options.maxPageSize() + ".");
        }
        return value;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("AzureAIPersistentClient is closed.");
        }
    }

    private void cancelRunBestEffort(String threadId, String runId) {
        try {
            transport.cancelRunAsync(threadId, runId, new DefaultRunCancellation());
        } catch (RuntimeException ignored) {
            // The local result has already settled; service cancellation is best effort.
        }
    }

    private final class PollOperation implements AutoCloseable {
        private final String threadId;
        private final String runId;
        private final RunCancellation cancellation;
        private final DefaultRunCancellation requestCancellation = new DefaultRunCancellation();
        private final CompletableFuture<PersistentRun> result = new CompletableFuture<>();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final ScheduledFuture<?> deadlineTask;
        private final RunCancellationRegistration registration;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final boolean cancelRemoteOnTimeoutOrCancellation;
        private int attempt;

        private PollOperation(
                String threadId,
                String runId,
                RunCancellation cancellation,
                boolean cancelRemoteOnTimeoutOrCancellation) {
            this.threadId = threadId;
            this.runId = runId;
            this.cancellation = cancellation;
            this.cancelRemoteOnTimeoutOrCancellation = cancelRemoteOnTimeoutOrCancellation;
            registration = RunCancellations.register(cancellation, this::cancel);
            deadlineTask = scheduler.schedule(
                    this::timeout, Math.max(1, options.timeout().toMillis()), TimeUnit.MILLISECONDS);
        }

        private void pollNow() {
            if (finished.get()) {
                return;
            }
            if (cancellation.isCancellationRequested()) {
                cancel();
                return;
            }
            transport.getRunAsync(threadId, runId, requestCancellation).whenComplete((run, failure) -> {
                if (finished.get()) {
                    return;
                }
                if (failure != null) {
                    if (finished.compareAndSet(false, true)) {
                        result.completeExceptionally(unwrap(failure));
                    }
                    return;
                }
                if (!run.status().isKnown()) {
                    if (finished.compareAndSet(false, true)) {
                        result.completeExceptionally(new AzureAIPersistentException(
                                "Persistent service returned an unknown run status.",
                                null,
                                AzureAIPersistentException.Kind.PROTOCOL,
                                null,
                                null,
                                "unknown_run_status",
                                null));
                    }
                    return;
                }
                if (run.status().isTerminal()) {
                    if (finished.compareAndSet(false, true)) {
                        result.complete(run);
                    }
                    return;
                }
                scheduleNext();
            });
        }

        private void scheduleNext() {
            long initial = options.initialPollDelay().toMillis();
            long maximum = options.maxPollDelay().toMillis();
            long exponential = initial;
            for (int index = 0; index < Math.min(attempt++, 30); index++) {
                if (exponential >= maximum / 2) {
                    exponential = maximum;
                    break;
                }
                exponential *= 2;
            }
            long bounded = Math.min(exponential, maximum);
            double jitter = options.pollJitter();
            long delta = Math.round(bounded * jitter);
            long low = Math.max(1, bounded - delta);
            long high = Math.max(low + 1, bounded + delta + 1);
            long delay = jitter == 0 ? bounded : ThreadLocalRandom.current().nextLong(low, high);
            ScheduledFuture<?> next = scheduler.schedule(this::pollNow, delay, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> prior = scheduled.getAndSet(next);
            if (prior != null && !prior.isDone()) {
                prior.cancel(false);
            }
            if (finished.get()) {
                next.cancel(false);
            }
        }

        private void cancel() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            requestCancellation.cancel();
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            if (cancelRemoteOnTimeoutOrCancellation) {
                transport.cancelRunAsync(threadId, runId, new DefaultRunCancellation());
            }
            result.completeExceptionally(new RunCancelledException());
        }

        private void timeout() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            requestCancellation.cancel();
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
            if (cancelRemoteOnTimeoutOrCancellation) {
                transport.cancelRunAsync(threadId, runId, new DefaultRunCancellation());
            }
            result.completeExceptionally(new TimeoutException(
                    "Persistent run did not reach a terminal state before the configured timeout."));
        }

        @Override
        public void close() {
            registration.close();
            deadlineTask.cancel(false);
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
