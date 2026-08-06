// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

abstract class AbstractOrchestration<O> implements Orchestration<O> {
    private final String id;

    private final OrchestrationPattern pattern;

    private final List<OrchestrationParticipant> participants;

    private final OrchestrationContinuationOptions continuationOptions;

    private final ExecutorService controlExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Set<RunHandle<OrchestrationResult<O>>> activeRuns = ConcurrentHashMap.newKeySet();

    private final Object continuationLock = new Object();

    private final LinkedHashMap<String, PendingContinuation> pendingContinuations = new LinkedHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    AbstractOrchestration(
            String id,
            OrchestrationPattern pattern,
            List<OrchestrationParticipant> participants,
            OrchestrationContinuationOptions continuationOptions) {
        this.id = OrchestrationValidation.requireId(id, "id");
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.participants = OrchestrationValidation.copyParticipants(participants);
        this.continuationOptions = Objects.requireNonNull(continuationOptions, "continuationOptions");
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final OrchestrationPattern pattern() {
        return pattern;
    }

    @Override
    public final List<OrchestrationParticipant> participants() {
        return participants;
    }

    @Override
    public final RunHandle<OrchestrationResult<O>> startRun(
            List<Message> messages, OrchestrationRunOptions options, RunCancellation cancellation) {
        requireOpen();
        List<Message> input = OrchestrationValidation.copyMessages(messages);
        OrchestrationRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        RunHandleSource<OrchestrationResult<O>> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        launchInitial(input, checkedOptions, source, ignored -> {}, ignored -> {});
        return source.handle();
    }

    @Override
    public final RunHandle<OrchestrationResult<O>> startResume(
            OrchestrationContinuation continuation,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunCancellation cancellation) {
        requireOpen();
        OrchestrationRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        PendingContinuation pending = consumeContinuation(continuation, input, checkedOptions);
        RunHandleSource<OrchestrationResult<O>> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        launchResume(pending, input, checkedOptions, source, ignored -> {}, ignored -> {});
        return source.handle();
    }

    @Override
    public final Flow.Publisher<OrchestrationEvent> runStreaming(
            List<Message> messages, OrchestrationRunOptions options, RunCancellation cancellation) {
        requireOpen();
        List<Message> input = OrchestrationValidation.copyMessages(messages);
        OrchestrationRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        return eventPublisher(
                checkedOptions,
                cancellation,
                (source, events, terminal) -> launchInitial(input, checkedOptions, source, events, terminal));
    }

    @Override
    public final Flow.Publisher<OrchestrationEvent> resumeStreaming(
            OrchestrationContinuation continuation,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunCancellation cancellation) {
        requireOpen();
        OrchestrationContinuation checkedContinuation = Objects.requireNonNull(continuation, "continuation");
        OrchestrationResumeInput checkedInput = Objects.requireNonNull(input, "input");
        OrchestrationRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        return eventPublisher(checkedOptions, cancellation, (source, events, terminal) -> {
            PendingContinuation pending = consumeContinuation(checkedContinuation, checkedInput, checkedOptions);
            launchResume(pending, checkedInput, checkedOptions, source, events, terminal);
        });
    }

    @Override
    public final void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeRuns.forEach(RunHandle::cancel);
        activeRuns.clear();
        List<PendingContinuation> abandoned;
        synchronized (continuationLock) {
            abandoned = List.copyOf(pendingContinuations.values());
            pendingContinuations.clear();
        }
        abandoned.forEach(PendingContinuation::discard);
        controlExecutor.close();
    }

    abstract CompletionStage<OrchestrationResult<O>> execute(
            OrchestrationExecutionContext<O> context, List<Message> input);

    final OrchestrationContinuation suspend(
            OrchestrationExecutionContext<O> context,
            OrchestrationContinuationKind kind,
            String participantId,
            AgentContinuation agentContinuation,
            List<Message> transcript,
            String prompt,
            ResumeExecution<O> resumeExecution) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resumeExecution, "resumeExecution");
        OrchestrationParticipant participant = null;
        Runnable discard = () -> {};
        if (kind == OrchestrationContinuationKind.APPROVAL) {
            if (participantId == null || agentContinuation == null) {
                throw new OrchestrationContinuationException(
                        "Approval continuations require a participant and AgentContinuation.");
            }
            participant = participant(participantId);
            context.requireApprovalResumeSupported(participant, agentContinuation);
            OrchestrationParticipant approvalParticipant = participant;
            discard = () -> context.abandonApproval(approvalParticipant, agentContinuation);
        } else if (agentContinuation != null) {
            throw new OrchestrationContinuationException(
                    "Only APPROVAL continuations may contain an AgentContinuation.");
        }
        OrchestrationContinuation descriptor = new OrchestrationContinuation(
                context.runId() + ":continuation:" + UUID.randomUUID(),
                id,
                context.runId(),
                pattern,
                kind,
                participantId,
                agentContinuation,
                transcript,
                prompt,
                false);
        PendingContinuation pending =
                new PendingContinuation(descriptor, context, resumeExecution, discard, System.nanoTime());
        registerContinuation(pending);
        return descriptor;
    }

    final int pendingContinuationCountForDiagnostics() {
        List<PendingContinuation> expired;
        int size;
        synchronized (continuationLock) {
            expired = removeExpiredLocked(System.nanoTime());
            size = pendingContinuations.size();
        }
        expired.forEach(PendingContinuation::discard);
        return size;
    }

    private OrchestrationParticipant participant(String participantId) {
        return participants.stream()
                .filter(candidate -> candidate.id().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new OrchestrationContinuationException(
                        "Continuation participant '" + participantId + "' is not registered."));
    }

    private Flow.Publisher<OrchestrationEvent> eventPublisher(
            OrchestrationRunOptions options, RunCancellation cancellation, StreamLauncher<O> launcher) {
        AtomicReference<RunHandle<OrchestrationResult<O>>> handle = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<OrchestrationEvent>> publisherReference = new AtomicReference<>();
        SingleSubscriberPublisher<OrchestrationEvent> publisher = new SingleSubscriberPublisher<>(
                () -> {
                    requireOpen();
                    RunHandleSource<OrchestrationResult<O>> source = new RunHandleSource<>(cancellation);
                    handle.set(source.handle());
                    SingleSubscriberPublisher<OrchestrationEvent> current = publisherReference.get();
                    launcher.launch(source, current::emit, failure -> {
                        if (failure == null) {
                            current.complete();
                        } else {
                            current.fail(failure);
                        }
                    });
                },
                () -> {
                    RunHandle<OrchestrationResult<O>> current = handle.get();
                    if (current == null) {
                        cancellation.cancel();
                    } else {
                        current.cancel();
                    }
                },
                options.maxBufferedEvents(),
                OrchestrationStreamingBufferOverflowException::new);
        publisherReference.set(publisher);
        return publisher;
    }

    private void launchInitial(
            List<Message> input,
            OrchestrationRunOptions options,
            RunHandleSource<OrchestrationResult<O>> source,
            Consumer<OrchestrationEvent> events,
            Consumer<Throwable> terminal) {
        String runId = OrchestrationExecutionContext.newRunId(options);
        OrchestrationExecutionContext<O> context =
                new OrchestrationExecutionContext<>(id, runId, options, source, events);
        launch(context, source, () -> execute(context, input), true, () -> {}, terminal);
    }

    private void launchResume(
            PendingContinuation pending,
            OrchestrationResumeInput input,
            OrchestrationRunOptions options,
            RunHandleSource<OrchestrationResult<O>> source,
            Consumer<OrchestrationEvent> events,
            Consumer<Throwable> terminal) {
        OrchestrationExecutionContext.Snapshot snapshot = pending.snapshot;
        if (snapshot == null) {
            pending.discard();
            Throwable failure = new OrchestrationContinuationException("Continuation state is not ready to resume.");
            source.tryFail(failure);
            terminal.accept(failure);
            return;
        }
        OrchestrationExecutionContext<O> context =
                new OrchestrationExecutionContext<>(id, pending.descriptor.runId(), options, source, events, snapshot);
        launch(
                context,
                source,
                () -> pending.resumeExecution.resume(context, input),
                false,
                pending::discard,
                terminal);
    }

    private void launch(
            OrchestrationExecutionContext<O> context,
            RunHandleSource<OrchestrationResult<O>> source,
            Supplier<CompletionStage<OrchestrationResult<O>>> execution,
            boolean initial,
            Runnable unsuccessfulCleanup,
            Consumer<Throwable> terminal) {
        RunHandle<OrchestrationResult<O>> handle = source.handle();
        activeRuns.add(handle);
        try {
            controlExecutor.execute(
                    () -> beginExecution(context, source, handle, execution, initial, unsuccessfulCleanup, terminal));
        } catch (RuntimeException failure) {
            activeRuns.remove(handle);
            unsuccessfulCleanup.run();
            discardPendingForContext(context);
            context.close();
            Throwable normalized = normalizeFailure(failure);
            source.tryFail(normalized);
            terminal.accept(normalized);
        }
    }

    private void beginExecution(
            OrchestrationExecutionContext<O> context,
            RunHandleSource<OrchestrationResult<O>> source,
            RunHandle<OrchestrationResult<O>> handle,
            Supplier<CompletionStage<OrchestrationResult<O>>> executionSupplier,
            boolean initial,
            Runnable unsuccessfulCleanup,
            Consumer<Throwable> terminal) {
        if (source.cancellation().isCancellationRequested()) {
            unsuccessfulCleanup.run();
            finishCancellation(context, handle, terminal);
            return;
        }
        if (initial) {
            context.emit(
                    OrchestrationEventType.RUN_STARTED,
                    null,
                    -1,
                    null,
                    StateValue.object(Map.of("orchestrationId", StateValue.string(id))));
        }
        CompletionStage<OrchestrationResult<O>> execution;
        try {
            execution = Objects.requireNonNull(executionSupplier.get(), "execute returned null");
        } catch (Throwable failure) {
            unsuccessfulCleanup.run();
            finishFailure(context, source, handle, failure, terminal);
            return;
        }
        execution.whenComplete((result, failure) -> {
            if (source.cancellation().isCancellationRequested()) {
                unsuccessfulCleanup.run();
                finishCancellation(context, handle, terminal);
            } else if (failure != null) {
                unsuccessfulCleanup.run();
                finishFailure(context, source, handle, failure, terminal);
            } else if (result == null) {
                unsuccessfulCleanup.run();
                finishFailure(
                        context,
                        source,
                        handle,
                        new OrchestrationExecutionException("Orchestration execution completed with null."),
                        terminal);
            } else {
                finishResult(context, source, handle, result, terminal);
            }
        });
    }

    private void finishResult(
            OrchestrationExecutionContext<O> context,
            RunHandleSource<OrchestrationResult<O>> source,
            RunHandle<OrchestrationResult<O>> handle,
            OrchestrationResult<O> result,
            Consumer<Throwable> terminal) {
        if (!context.runId().equals(result.runId())) {
            finishFailure(
                    context,
                    source,
                    handle,
                    new OrchestrationExecutionException("Result runId does not match the active logical run."),
                    terminal);
            return;
        }
        OrchestrationEventType terminalType =
                switch (result.outcome()) {
                    case COMPLETED, COMPLETED_WITH_ERRORS -> OrchestrationEventType.RUN_COMPLETED;
                    case INPUT_REQUIRED -> OrchestrationEventType.INPUT_REQUIRED;
                    case TERMINATED, UNSOLVED, FAILED -> OrchestrationEventType.RUN_TERMINATED;
                };
        context.emit(
                terminalType,
                null,
                result.turns(),
                null,
                StateValue.object(Map.of(
                        "outcome", StateValue.string(result.outcome().name()),
                        "reason", StateValue.string(result.terminationReason().name()))));
        if (result.continuation() != null) {
            sealContinuation(result.continuation(), context.snapshot());
        }
        OrchestrationResult<O> completed = result.withEvents(context.events());
        activeRuns.remove(handle);
        source.tryComplete(completed);
        terminal.accept(null);
        closeContext(context);
    }

    private void finishFailure(
            OrchestrationExecutionContext<O> context,
            RunHandleSource<OrchestrationResult<O>> source,
            RunHandle<OrchestrationResult<O>> handle,
            Throwable failure,
            Consumer<Throwable> terminal) {
        Throwable normalized = normalizeFailure(failure);
        discardPendingForContext(context);
        try {
            context.emit(
                    OrchestrationEventType.RUN_FAILED,
                    null,
                    -1,
                    null,
                    StateValue.object(Map.of(
                            "errorType", StateValue.string(normalized.getClass().getName()),
                            "message", StateValue.string(safeMessage(normalized)))));
        } catch (RuntimeException ignored) {
            // Preserve the original execution failure.
        }
        activeRuns.remove(handle);
        source.tryFail(normalized);
        terminal.accept(normalized);
        closeContext(context);
    }

    private void finishCancellation(
            OrchestrationExecutionContext<O> context,
            RunHandle<OrchestrationResult<O>> handle,
            Consumer<Throwable> terminal) {
        RunCancelledException cancellation = new RunCancelledException();
        discardPendingForContext(context);
        try {
            context.emit(
                    OrchestrationEventType.RUN_CANCELLED,
                    null,
                    -1,
                    null,
                    StateValue.object(Map.of("outcome", StateValue.string("CANCELLED"))));
        } catch (RuntimeException ignored) {
            // Subscription cancellation can make the event sink unavailable.
        }
        activeRuns.remove(handle);
        terminal.accept(cancellation);
        closeContext(context);
    }

    private void registerContinuation(PendingContinuation pending) {
        ArrayList<PendingContinuation> removed = new ArrayList<>();
        synchronized (continuationLock) {
            removed.addAll(removeExpiredLocked(System.nanoTime()));
            while (pendingContinuations.size() >= continuationOptions.maxPendingContinuations()) {
                Iterator<PendingContinuation> values =
                        pendingContinuations.values().iterator();
                PendingContinuation eldest = values.next();
                values.remove();
                removed.add(eldest);
            }
            pendingContinuations.put(pending.descriptor.continuationId(), pending);
        }
        removed.forEach(PendingContinuation::discard);
        long ttlNanos = continuationOptions.timeToLive().toNanos();
        String continuationId = pending.descriptor.continuationId();
        long createdAtNanos = pending.createdAtNanos;
        WeakReference<AbstractOrchestration<O>> owner = new WeakReference<>(this);
        CompletableFuture.delayedExecutor(ttlNanos, TimeUnit.NANOSECONDS).execute(() -> {
            AbstractOrchestration<O> current = owner.get();
            if (current != null) {
                current.expireContinuation(continuationId, createdAtNanos);
            }
        });
    }

    private PendingContinuation consumeContinuation(
            OrchestrationContinuation continuation, OrchestrationResumeInput input, OrchestrationRunOptions options) {
        OrchestrationContinuation checked = Objects.requireNonNull(continuation, "continuation");
        OrchestrationResumeInput checkedInput = Objects.requireNonNull(input, "input");
        if (!id.equals(checked.orchestrationId())) {
            throw new OrchestrationContinuationException(
                    "Continuation belongs to orchestration '" + checked.orchestrationId() + "', not '" + id + "'.");
        }
        if (pattern != checked.pattern()) {
            throw new OrchestrationContinuationException(
                    "Continuation belongs to pattern " + checked.pattern() + ", not " + pattern + ".");
        }
        if (checked.kind() != checkedInput.kind()) {
            throw new OrchestrationContinuationException(
                    "Continuation kind " + checked.kind() + " does not accept " + checkedInput.kind() + " input.");
        }
        if (options.runId() != null && !options.runId().equals(checked.runId())) {
            throw new OrchestrationContinuationException(
                    "Resume runId must match the suspended logical run '" + checked.runId() + "'.");
        }
        List<PendingContinuation> expired;
        PendingContinuation pending;
        String validationFailure = null;
        synchronized (continuationLock) {
            expired = removeExpiredLocked(System.nanoTime());
            pending = pendingContinuations.get(checked.continuationId());
            if (pending != null) {
                if (!pending.descriptor.equals(checked)) {
                    validationFailure =
                            "Continuation identity does not match its run, participant, pattern, or approval state.";
                } else if (pending.snapshot == null) {
                    validationFailure = "Continuation state is not ready to resume.";
                } else {
                    pendingContinuations.remove(checked.continuationId());
                }
            }
        }
        expired.forEach(PendingContinuation::discard);
        if (validationFailure != null) {
            throw new OrchestrationContinuationException(validationFailure);
        }
        if (pending == null) {
            throw new OrchestrationContinuationException(
                    "Continuation is stale, expired, already consumed, or belongs to another orchestration instance.");
        }
        return pending;
    }

    private void sealContinuation(
            OrchestrationContinuation continuation, OrchestrationExecutionContext.Snapshot snapshot) {
        synchronized (continuationLock) {
            PendingContinuation pending = pendingContinuations.get(continuation.continuationId());
            if (pending != null && pending.descriptor.equals(continuation)) {
                pending.snapshot = snapshot;
            }
        }
    }

    private void expireContinuation(String continuationId, long createdAtNanos) {
        PendingContinuation expired = null;
        synchronized (continuationLock) {
            PendingContinuation candidate = pendingContinuations.get(continuationId);
            if (candidate != null
                    && candidate.createdAtNanos == createdAtNanos
                    && isExpired(candidate, System.nanoTime())) {
                expired = pendingContinuations.remove(continuationId);
            }
        }
        if (expired != null) {
            expired.discard();
        }
    }

    private List<PendingContinuation> removeExpiredLocked(long nowNanos) {
        ArrayList<PendingContinuation> expired = new ArrayList<>();
        Iterator<PendingContinuation> iterator = pendingContinuations.values().iterator();
        while (iterator.hasNext()) {
            PendingContinuation pending = iterator.next();
            if (isExpired(pending, nowNanos)) {
                iterator.remove();
                expired.add(pending);
            }
        }
        return expired;
    }

    private boolean isExpired(PendingContinuation pending, long nowNanos) {
        return nowNanos - pending.createdAtNanos
                >= continuationOptions.timeToLive().toNanos();
    }

    private void discardPendingForContext(OrchestrationExecutionContext<O> context) {
        ArrayList<PendingContinuation> discarded = new ArrayList<>();
        synchronized (continuationLock) {
            Iterator<PendingContinuation> iterator =
                    pendingContinuations.values().iterator();
            while (iterator.hasNext()) {
                PendingContinuation pending = iterator.next();
                if (pending.ownerContext == context) {
                    iterator.remove();
                    discarded.add(pending);
                }
            }
        }
        discarded.forEach(PendingContinuation::discard);
    }

    private static Throwable normalizeFailure(Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof AgentFrameworkException || cause instanceof Error) {
            return cause;
        }
        return new OrchestrationExecutionException("Orchestration execution failed.", cause);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void closeContext(OrchestrationExecutionContext<?> context) {
        try {
            context.close();
        } catch (RuntimeException ignored) {
            // Terminal delivery must not be suppressed by best-effort executor cleanup.
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new OrchestrationExecutionException("Orchestration '" + id + "' is closed.");
        }
    }

    @FunctionalInterface
    interface ResumeExecution<T> {
        CompletionStage<OrchestrationResult<T>> resume(
                OrchestrationExecutionContext<T> context, OrchestrationResumeInput input);
    }

    @FunctionalInterface
    private interface StreamLauncher<T> {
        void launch(
                RunHandleSource<OrchestrationResult<T>> source,
                Consumer<OrchestrationEvent> events,
                Consumer<Throwable> terminal);
    }

    private final class PendingContinuation {
        private final OrchestrationContinuation descriptor;

        private final OrchestrationExecutionContext<O> ownerContext;

        private final ResumeExecution<O> resumeExecution;

        private final Runnable discardAction;

        private final long createdAtNanos;

        private final AtomicBoolean discarded = new AtomicBoolean();

        private volatile OrchestrationExecutionContext.Snapshot snapshot;

        private PendingContinuation(
                OrchestrationContinuation descriptor,
                OrchestrationExecutionContext<O> ownerContext,
                ResumeExecution<O> resumeExecution,
                Runnable discardAction,
                long createdAtNanos) {
            this.descriptor = descriptor;
            this.ownerContext = ownerContext;
            this.resumeExecution = resumeExecution;
            this.discardAction = discardAction;
            this.createdAtNanos = createdAtNanos;
        }

        private void discard() {
            if (discarded.compareAndSet(false, true)) {
                discardAction.run();
            }
        }
    }
}
