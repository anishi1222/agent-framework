// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final Object stateLock = new Object();

    private final AtomicBoolean runActive = new AtomicBoolean();

    private final LinkedHashMap<String, StateValue> state = new LinkedHashMap<>();

    private final ArrayList<Message> messages = new ArrayList<>();

    private StateValue.ObjectValue pendingRun;

    private long revision;

    /** Creates a new session with a generated identity and no persisted revision. */
    public AgentSession() {
        this(UUID.randomUUID().toString());
    }

    /**
     * Creates a new session with a caller-selected immutable identity.
     *
     * @param sessionId non-blank identity
     */
    public AgentSession(String sessionId) {
        this(sessionId, AgentSessionStateBag.empty(), List.of(), null, SessionStore.CREATE_ONLY);
    }

    private AgentSession(
            String sessionId,
            AgentSessionStateBag initialState,
            List<Message> initialMessages,
            StateValue.ObjectValue pendingRun,
            long revision) {
        this.sessionId = AgentValidation.requireNonBlank(sessionId, "sessionId");
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
                snapshot.sessionId(), snapshot.state(), snapshot.messages(), snapshot.pendingRun(), revision);
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
}
