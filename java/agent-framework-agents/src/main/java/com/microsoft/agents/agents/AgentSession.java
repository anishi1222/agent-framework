// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Owns the thread-safe mutable runtime for one immutable agent-session identity.
 *
 * <p>State and history accessors return immutable detached snapshots. Mutation is serialized through
 * this runtime. Concurrent runs fail deterministically through a per-session gate rather than racing
 * or silently losing an update.
 */
public final class AgentSession {
    private final String sessionId;

    private final boolean automaticPersistenceEnabled;

    private final Object stateLock = new Object();

    private final AtomicBoolean runActive = new AtomicBoolean();

    private final LinkedHashMap<String, StateValue> state = new LinkedHashMap<>();

    private final ArrayList<Message> messages = new ArrayList<>();

    private StateValue.ObjectValue pendingRun;

    private long revision;

    /** Creates a new session with a generated identity and no persisted revision. */
    public AgentSession() {
        this(UUID.randomUUID().toString(), true);
    }

    /**
     * Creates a new session with a caller-selected immutable identity.
     *
     * @param sessionId non-blank identity
     */
    public AgentSession(String sessionId) {
        this(sessionId, true);
    }

    private AgentSession(String sessionId, boolean automaticPersistenceEnabled) {
        this(
                sessionId,
                AgentSessionStateBag.empty(),
                List.of(),
                null,
                SessionStore.CREATE_ONLY,
                automaticPersistenceEnabled);
    }

    /**
     * Creates a session whose state remains process-local during automatic agent persistence.
     *
     * <p>Explicit {@link ChatAgent#saveSessionAsync(AgentSession)} calls can still persist the
     * session. This mode is intended for child runtimes whose execution context must not leak into a
     * configured parent {@link SessionStore}.
     *
     * @param sessionId non-blank identity
     * @return process-local session
     */
    public static AgentSession processLocal(String sessionId) {
        return new AgentSession(sessionId, false);
    }

    private AgentSession(
            String sessionId,
            AgentSessionStateBag initialState,
            List<Message> initialMessages,
            StateValue.ObjectValue pendingRun,
            long revision,
            boolean automaticPersistenceEnabled) {
        this.sessionId = AgentValidation.requireNonBlank(sessionId, "sessionId");
        this.automaticPersistenceEnabled = automaticPersistenceEnabled;
        this.state.putAll(
                AgentValidation.requireNonNull(initialState, "initialState").values());
        this.messages.addAll(AgentValidation.copyMessages(initialMessages));
        this.pendingRun = pendingRun;
        this.revision = revision;
    }

    /**
     * Returns the immutable session identity.
     *
     * @return session identifier
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns an immutable detached state view.
     *
     * @return state snapshot
     */
    public AgentSessionStateBag state() {
        synchronized (stateLock) {
            return new AgentSessionStateBag(state);
        }
    }

    /**
     * Returns ordered immutable detached history.
     *
     * @return conversation messages
     */
    public List<Message> messages() {
        synchronized (stateLock) {
            return List.copyOf(messages);
        }
    }

    /**
     * Associates one JSON-shaped value with a state key.
     *
     * @param key state key
     * @param value immutable state value
     * @return prior value, or {@code null}
     */
    public StateValue putState(String key, StateValue value) {
        synchronized (stateLock) {
            return state.put(
                    AgentValidation.requireNonBlank(key, "key"), AgentValidation.requireNonNull(value, "value"));
        }
    }

    /**
     * Atomically updates one state value.
     *
     * @param key state key
     * @param updater update function receiving the current value or {@code null}
     * @return replacement value
     */
    public StateValue updateState(String key, UnaryOperator<StateValue> updater) {
        String safeKey = AgentValidation.requireNonBlank(key, "key");
        AgentValidation.requireNonNull(updater, "updater");
        synchronized (stateLock) {
            StateValue replacement = AgentValidation.requireNonNull(updater.apply(state.get(safeKey)), "replacement");
            state.put(safeKey, replacement);
            return replacement;
        }
    }

    /**
     * Removes one state value.
     *
     * @param key state key
     * @return prior value, or {@code null}
     */
    public StateValue removeState(String key) {
        synchronized (stateLock) {
            return state.remove(AgentValidation.requireNonBlank(key, "key"));
        }
    }

    /**
     * Captures a detached immutable snapshot.
     *
     * @return session snapshot
     */
    public AgentSessionSnapshot snapshot() {
        synchronized (stateLock) {
            return new AgentSessionSnapshot(
                    sessionId, List.copyOf(messages), new AgentSessionStateBag(state), pendingRun);
        }
    }

    /**
     * Restores mutable history, provider state, and pending-run data from a detached snapshot while
     * preserving this runtime's identity and optimistic-store revision.
     *
     * <p>The operation is intended for bounded retry and fresh-context orchestration between active
     * runs. Restoring a different session identity is rejected.
     *
     * @param snapshot detached snapshot with the same session identity
     */
    public void restoreSnapshot(AgentSessionSnapshot snapshot) {
        restoreSnapshotPreservingState(snapshot, ignored -> false);
    }

    /**
     * Restores a detached snapshot while atomically retaining selected current state entries.
     *
     * <p>Keys selected by {@code preserveStateKey} are taken from the current runtime, not the
     * snapshot. This supports fresh-context orchestration around process-local resources that cannot
     * safely be rewound.
     *
     * @param snapshot detached snapshot with the same session identity
     * @param preserveStateKey predicate selecting current state keys to retain
     */
    public void restoreSnapshotPreservingState(AgentSessionSnapshot snapshot, Predicate<String> preserveStateKey) {
        AgentSessionSnapshot safe = AgentValidation.requireNonNull(snapshot, "snapshot");
        Predicate<String> safePredicate = AgentValidation.requireNonNull(preserveStateKey, "preserveStateKey");
        if (!sessionId.equals(safe.sessionId())) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Snapshot session identity does not match this runtime.");
        }
        synchronized (stateLock) {
            LinkedHashMap<String, StateValue> preserved = new LinkedHashMap<>();
            state.forEach((key, value) -> {
                if (safePredicate.test(key)) {
                    preserved.put(key, value);
                }
            });
            messages.clear();
            messages.addAll(safe.messages());
            state.clear();
            state.putAll(safe.state().values());
            state.keySet().removeIf(safePredicate);
            state.putAll(preserved);
            pendingRun = safe.pendingRun();
        }
    }

    /**
     * Acquires exclusive run ownership for this session.
     *
     * <p>The lease is intended for higher-level runtimes that must perform multiple agent
     * invocations as one logical session run. Close it exactly once after the complete operation.
     *
     * @return active exclusive run lease
     * @throws SessionBusyException when another run already owns the session
     */
    public RunLease acquireRunLease() {
        beginRun();
        return new RunLease(this);
    }

    /**
     * Returns the current opaque persisted revision.
     *
     * @return {@link SessionStore#CREATE_ONLY} before the first successful save, otherwise a positive
     *     opaque revision
     */
    public long revision() {
        synchronized (stateLock) {
            return revision;
        }
    }

    static AgentSession restore(AgentSessionSnapshot snapshot, long revision) {
        AgentValidation.requireNonNull(snapshot, "snapshot");
        if (revision <= 0) {
            throw new com.microsoft.agents.core.ValidationException("revision must be greater than zero.");
        }
        return new AgentSession(
                snapshot.sessionId(), snapshot.state(), snapshot.messages(), snapshot.pendingRun(), revision, true);
    }

    boolean automaticPersistenceEnabled() {
        return automaticPersistenceEnabled;
    }

    void appendMessages(List<Message> additions) {
        List<Message> safe = AgentValidation.copyMessages(additions);
        synchronized (stateLock) {
            messages.addAll(safe);
        }
    }

    void replaceMessages(List<Message> replacement) {
        List<Message> safe = AgentValidation.copyMessages(replacement);
        synchronized (stateLock) {
            messages.clear();
            messages.addAll(safe);
        }
    }

    StateValue.ObjectValue pendingRun() {
        synchronized (stateLock) {
            return pendingRun;
        }
    }

    void pendingRun(StateValue.ObjectValue value) {
        synchronized (stateLock) {
            pendingRun = value;
        }
    }

    void persisted(long newRevision) {
        if (newRevision <= 0) {
            throw new com.microsoft.agents.core.ValidationException("newRevision must be greater than zero.");
        }
        synchronized (stateLock) {
            revision = newRevision;
        }
    }

    void beginRun() {
        if (!runActive.compareAndSet(false, true)) {
            throw new SessionBusyException("Session '" + sessionId + "' already has an active run.");
        }
    }

    void endRun() {
        if (!runActive.compareAndSet(true, false)) {
            throw new IllegalStateException("Session run gate was not active.");
        }
    }

    /** Represents exclusive ownership of one session run gate. */
    public static final class RunLease implements AutoCloseable {
        private final AgentSession session;

        private final AtomicBoolean active = new AtomicBoolean(true);

        private RunLease(AgentSession session) {
            this.session = session;
        }

        AgentSession session() {
            if (!active.get()) {
                throw new IllegalStateException("Session run lease is closed.");
            }
            return session;
        }

        /** Releases this lease. Repeated calls have no effect. */
        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                session.endRun();
            }
        }
    }
}
