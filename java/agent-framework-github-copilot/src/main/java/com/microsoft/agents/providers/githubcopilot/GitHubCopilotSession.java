// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.rpc.MessageOptions;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wraps one external Copilot CLI session with bounded framework-owned events.
 *
 * <p>Closing this object releases the in-process subscription only. Persisted CLI session data is
 * external process storage and is deleted only through {@link GitHubCopilotClient#deleteSessionAsync}.
 */
public final class GitHubCopilotSession implements AutoCloseable {
    private final CopilotSession delegate;

    private final GitHubCopilotSessionConfig config;

    private final GitHubCopilotSdkMapper mapper;

    private final Duration requestTimeout;

    private final SubmissionPublisher<GitHubCopilotEvent> events;

    private final AtomicLong sequence = new AtomicLong();

    private final AtomicLong unknownEvents = new AtomicLong();

    private final AtomicReference<GitHubCopilotEvent> lastAssistant = new AtomicReference<>();

    private final AtomicReference<CompletableFuture<Void>> idleSignal = new AtomicReference<>();

    private final AtomicBoolean requestActive = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();

    private final Closeable upstreamSubscription;

    private final Consumer<GitHubCopilotSession> onClose;

    private final AtomicBoolean eventPublisherClosed = new AtomicBoolean();

    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();

    GitHubCopilotSession(
            CopilotSession delegate,
            GitHubCopilotSessionConfig config,
            GitHubCopilotSdkMapper mapper,
            Duration requestTimeout,
            GitHubCopilotLimits limits,
            Executor executor,
            Consumer<GitHubCopilotSession> onClose) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.onClose = Objects.requireNonNull(onClose, "onClose");
        events = new SubmissionPublisher<>(executor, limits.maxBufferedEvents());
        upstreamSubscription = delegate.on(this::onEvent);
    }

    /**
     * Returns the stable external session identity.
     *
     * @return session identity
     */
    public String sessionId() {
        return delegate.getSessionId();
    }

    /**
     * Returns the official SDK workspace path used by infinite sessions.
     *
     * @return workspace path, or {@code null} when unavailable
     */
    public String workspacePath() {
        return delegate.getWorkspacePath();
    }

    /**
     * Returns immutable session configuration.
     *
     * @return session configuration
     */
    public GitHubCopilotSessionConfig config() {
        return config;
    }

    /**
     * Returns a hot bounded event fanout.
     *
     * @return event publisher
     */
    public Flow.Publisher<GitHubCopilotEvent> events() {
        return events;
    }

    /**
     * Returns the count of retained upstream event types not yet specially mapped.
     *
     * @return unknown-event count
     */
    public long unknownEventCount() {
        return unknownEvents.get();
    }

    AutoCloseable addListener(Consumer<GitHubCopilotEvent> listener) {
        return addListener(listener, ignored -> {});
    }

    AutoCloseable addListener(Consumer<GitHubCopilotEvent> listener, Consumer<Throwable> failureListener) {
        SessionListener safe = new SessionListener(
                Objects.requireNonNull(listener, "listener"),
                Objects.requireNonNull(failureListener, "failureListener"));
        listeners.add(safe);
        return () -> listeners.remove(safe);
    }

    /**
     * Sends one new prompt without waiting for the session to become idle.
     *
     * <p>A second send is rejected until an idle event or abort completes.
     *
     * @param prompt new user prompt
     * @return assigned message identity
     */
    public CompletionStage<String> sendAsync(String prompt) {
        ensureRequestAllowed(prompt);
        CompletableFuture<String> upstream = delegate.send(new MessageOptions().setPrompt(prompt))
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<String> result = new CompletableFuture<>();
        upstream.whenComplete((messageId, failure) -> {
            if (failure != null) {
                requestActive.set(false);
                result.completeExceptionally(GitHubCopilotClient.normalize(failure, "request"));
            } else {
                result.complete(messageId);
            }
        });
        return result.minimalCompletionStage();
    }

    /**
     * Sends one new prompt and waits for the session to become idle.
     *
     * @param prompt new user prompt
     * @return final assistant event, or {@code null} when no assistant message was emitted
     */
    public CompletionStage<GitHubCopilotEvent> sendAndWaitAsync(String prompt) {
        ensureRequestAllowed(prompt);
        lastAssistant.set(null);
        CompletableFuture<GitHubCopilotEvent> result = new CompletableFuture<>();
        CompletableFuture<Void> turnIdle = new CompletableFuture<>();
        if (!idleSignal.compareAndSet(null, turnIdle)) {
            requestActive.set(false);
            return CompletableFuture.failedStage(new IllegalStateException("A Copilot idle waiter is already active."));
        }
        delegate.sendAndWait(new MessageOptions().setPrompt(prompt), requestTimeout.toMillis())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        idleSignal.compareAndSet(turnIdle, null);
                        requestActive.set(false);
                        result.completeExceptionally(GitHubCopilotClient.normalize(failure, "request"));
                    } else {
                        turnIdle.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                                .whenComplete((idle, idleFailure) -> {
                                    idleSignal.compareAndSet(turnIdle, null);
                                    requestActive.set(false);
                                    if (idleFailure != null) {
                                        result.completeExceptionally(
                                                GitHubCopilotClient.normalize(idleFailure, "event"));
                                    } else if (terminalFailure.get() != null) {
                                        result.completeExceptionally(
                                                GitHubCopilotClient.normalize(terminalFailure.get(), "event"));
                                    } else {
                                        result.complete(lastAssistant.get());
                                    }
                                });
                    }
                });
        return result.minimalCompletionStage();
    }

    /**
     * Requests cancellation of the current Copilot turn.
     *
     * @return abort acknowledgement
     */
    public CompletionStage<Void> abortAsync() {
        ensureOpen();
        CompletableFuture<Void> result = new CompletableFuture<>();
        delegate.abort()
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, failure) -> {
                    requestActive.set(false);
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(GitHubCopilotClient.normalize(failure, "abort"));
                    }
                });
        return result.minimalCompletionStage();
    }

    /**
     * Retrieves the external session event log as detached framework-owned events.
     *
     * @return mapped event list
     */
    public CompletionStage<List<GitHubCopilotEvent>> getMessagesAsync() {
        ensureOpen();
        CompletableFuture<List<GitHubCopilotEvent>> result = new CompletableFuture<>();
        delegate.getMessages()
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(source -> source.stream()
                        .map(event -> mapper.event(sessionId(), event, sequence.getAndIncrement(), unknownEvents))
                        .toList())
                .whenComplete((events, failure) -> {
                    if (failure == null) {
                        result.complete(events);
                    } else {
                        result.completeExceptionally(GitHubCopilotClient.normalize(failure, "messages"));
                    }
                });
        return result.minimalCompletionStage();
    }

    /**
     * Changes the model for subsequent turns through the official SDK.
     *
     * @param model model identifier
     * @return model-switch acknowledgement
     */
    public CompletionStage<Void> setModelAsync(String model) {
        return setModelAsync(model, null);
    }

    /**
     * Changes the model and optional reasoning effort through the official SDK.
     *
     * @param model model identifier
     * @param reasoningEffort optional reasoning effort
     * @return model-switch acknowledgement
     */
    public CompletionStage<Void> setModelAsync(String model, String reasoningEffort) {
        ensureOpen();
        String selectedModel = requireNonBlank(model, "model");
        String effort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
        return operation(delegate.setModel(selectedModel, effort), "set_model");
    }

    /**
     * Requests immediate infinite-session compaction through the official SDK.
     *
     * @return compaction acknowledgement
     */
    public CompletionStage<Void> compactAsync() {
        ensureOpen();
        return operation(delegate.compact(), "compact");
    }

    /**
     * Logs an informational message to the official session timeline.
     *
     * @param message log message
     * @return log acknowledgement
     */
    public CompletionStage<Void> logAsync(String message) {
        ensureOpen();
        return operation(delegate.log(requireNonBlank(message, "message")), "log");
    }

    /**
     * Logs a message to the official session timeline.
     *
     * @param message log message
     * @param level log level
     * @param ephemeral whether the message is transient
     * @return log acknowledgement
     */
    public CompletionStage<Void> logAsync(String message, GitHubCopilotLogLevel level, boolean ephemeral) {
        ensureOpen();
        return operation(
                delegate.log(
                        requireNonBlank(message, "message"),
                        Objects.requireNonNull(level, "level").sdkValue(),
                        ephemeral),
                "log");
    }

    /**
     * Aborts active work, stops event delivery, and releases this wrapper.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (requestActive.getAndSet(false)) {
            try {
                delegate.abort().get(Math.max(1, requestTimeout.toSeconds()), TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // The subsequent session close remains authoritative.
            }
        }
        try {
            upstreamSubscription.close();
        } catch (IOException exception) {
            closeEventPublisherExceptionally(exception);
        }
        delegate.close();
        closeEventPublisher();
        onClose.accept(this);
    }

    private void onEvent(SessionEvent source) {
        if (closed.get()) {
            return;
        }
        GitHubCopilotEvent event;
        try {
            event = mapper.event(sessionId(), source, sequence.getAndIncrement(), unknownEvents);
        } catch (RuntimeException failure) {
            requestActive.set(false);
            terminalFailure.compareAndSet(null, failure);
            CompletableFuture<Void> activeIdle = idleSignal.getAndSet(null);
            if (activeIdle != null) {
                activeIdle.completeExceptionally(failure);
            }
            closeEventPublisherExceptionally(failure);
            listeners.forEach(listener -> listener.onFailure().accept(failure));
            delegate.abort();
            return;
        }
        if (event.type() == GitHubCopilotEventType.ASSISTANT_MESSAGE) {
            lastAssistant.set(event);
        }
        if (event.type() == GitHubCopilotEventType.IDLE || event.type() == GitHubCopilotEventType.ERROR) {
            requestActive.set(false);
        }
        if (!eventPublisherClosed.get()) {
            try {
                int lag = events.offer(event, (subscriber, dropped) -> false);
                if (lag < 0) {
                    closeEventPublisherExceptionally(new GitHubCopilotProviderException(
                            "Copilot event subscriber exceeded the configured buffer.",
                            null,
                            "backpressure",
                            "event_buffer_overflow"));
                }
            } catch (IllegalStateException ignored) {
                // A concurrent close won the race; internal SDK callback listeners still complete.
            }
        }
        for (SessionListener listener : listeners) {
            try {
                listener.onEvent().accept(event);
            } catch (RuntimeException failure) {
                listener.onFailure().accept(failure);
            }
        }
        if (event.type() == GitHubCopilotEventType.IDLE || event.type() == GitHubCopilotEventType.ERROR) {
            CompletableFuture<Void> activeIdle = idleSignal.getAndSet(null);
            if (activeIdle != null) {
                activeIdle.complete(null);
            }
        }
    }

    private void ensureRequestAllowed(String prompt) {
        ensureOpen();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank.");
        }
        if (!requestActive.compareAndSet(false, true)) {
            throw new IllegalStateException("This Copilot session already has an active request.");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("GitHubCopilotSession is closed.");
        }
    }

    private CompletionStage<Void> operation(CompletableFuture<Void> upstream, String kind) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        upstream.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((ignored, failure) -> {
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(GitHubCopilotClient.normalize(failure, kind));
            }
        });
        return result.minimalCompletionStage();
    }

    private void closeEventPublisher() {
        if (eventPublisherClosed.compareAndSet(false, true)) {
            events.close();
        }
    }

    private void closeEventPublisherExceptionally(Throwable failure) {
        if (eventPublisherClosed.compareAndSet(false, true)) {
            events.closeExceptionally(failure);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private record SessionListener(Consumer<GitHubCopilotEvent> onEvent, Consumer<Throwable> onFailure) {}
}
