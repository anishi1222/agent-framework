// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class A2AEventValidator {
    private final boolean subscription;

    private final Map<String, Boolean> artifactCompletion = new HashMap<>();

    private boolean first = true;

    private boolean boundaryReached;

    private String taskId;

    private String contextId;

    private TaskState state;

    private Instant statusTimestamp;

    A2AEventValidator(boolean subscription) {
        this.subscription = subscription;
    }

    void accept(A2AStreamEvent event) {
        if (boundaryReached) {
            throw invalid("A2A stream emitted an event after its terminal or interrupted boundary.");
        }
        if (first) {
            first = false;
            if (subscription && !(event instanceof Task)) {
                throw invalid("SubscribeToTask must emit the current Task as its first event.");
            }
            if (!(event instanceof Task) && !(event instanceof Message)) {
                throw invalid("A2A stream must begin with a Task or Message.");
            }
        }
        switch (event) {
            case Task task -> acceptTask(task);
            case Message message -> acceptMessage(message);
            case TaskStatusUpdateEvent update -> acceptStatus(update);
            case TaskArtifactUpdateEvent update -> acceptArtifact(update);
        }
    }

    void verifyComplete() {
        if (first) {
            throw invalid("A2A stream completed without an event.");
        }
        if (!boundaryReached) {
            throw invalid("A2A stream ended before a terminal or interrupted boundary.");
        }
    }

    private void acceptTask(Task task) {
        correlate(task.id(), task.contextId());
        updateState(task.status());
        if (task.status().state().isTerminal() || task.status().state().isInterrupted()) {
            boundaryReached = true;
        }
    }

    private void acceptMessage(Message message) {
        if (taskId == null) {
            taskId = message.taskId();
            contextId = message.contextId();
            boundaryReached = true;
            return;
        }
        if (message.taskId() != null && !taskId.equals(message.taskId())) {
            throw invalid("A2A message taskId does not match the stream task.");
        }
        if (message.contextId() != null && !contextId.equals(message.contextId())) {
            throw invalid("A2A message contextId does not match the stream context.");
        }
    }

    private void acceptStatus(TaskStatusUpdateEvent update) {
        requireTask();
        correlate(update.taskId(), update.contextId());
        updateState(update.status());
        if (update.status().state().isTerminal() || update.status().state().isInterrupted()) {
            boundaryReached = true;
        }
    }

    private void acceptArtifact(TaskArtifactUpdateEvent update) {
        requireTask();
        correlate(update.taskId(), update.contextId());
        String id = update.artifact().artifactId();
        Boolean complete = artifactCompletion.get(id);
        if (Boolean.TRUE.equals(complete)) {
            throw invalid("A2A artifact received a chunk after lastChunk.");
        }
        if (update.append() && complete == null) {
            throw invalid("A2A artifact append arrived before its initial chunk.");
        }
        if (!update.append() && complete != null) {
            throw invalid("A2A artifact was replaced after its initial chunk.");
        }
        artifactCompletion.put(id, update.lastChunk());
    }

    private void updateState(TaskStatus status) {
        if (statusTimestamp != null
                && status.timestamp() != null
                && status.timestamp().isBefore(statusTimestamp)) {
            throw invalid("A2A status timestamps moved backwards.");
        }
        if (state != null && !transitionAllowed(state, status.state())) {
            throw invalid("Invalid A2A task transition " + state + " -> " + status.state() + ".");
        }
        state = status.state();
        if (status.timestamp() != null) {
            statusTimestamp = status.timestamp();
        }
    }

    private void correlate(String candidateTaskId, String candidateContextId) {
        if (taskId == null) {
            taskId = candidateTaskId;
            contextId = candidateContextId;
            return;
        }
        if (!taskId.equals(candidateTaskId)) {
            throw invalid("A2A stream task/context correlation changed.");
        }
        if (contextId == null) {
            contextId = candidateContextId;
        } else if (candidateContextId != null && !contextId.equals(candidateContextId)) {
            throw invalid("A2A stream task/context correlation changed.");
        }
    }

    private void requireTask() {
        if (taskId == null) {
            throw invalid("A2A update arrived before task correlation was established.");
        }
    }

    private static boolean transitionAllowed(TaskState previous, TaskState next) {
        if (previous == next) {
            return true;
        }
        if (previous.isTerminal()) {
            return false;
        }
        return switch (previous) {
            case TASK_STATE_SUBMITTED, TASK_STATE_WORKING ->
                next == TaskState.TASK_STATE_WORKING
                        || next == TaskState.TASK_STATE_INPUT_REQUIRED
                        || next == TaskState.TASK_STATE_AUTH_REQUIRED
                        || next.isTerminal();
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED ->
                next == TaskState.TASK_STATE_SUBMITTED || next == TaskState.TASK_STATE_WORKING || next.isTerminal();
            case TASK_STATE_UNSPECIFIED -> true;
            case TASK_STATE_COMPLETED, TASK_STATE_FAILED, TASK_STATE_CANCELED, TASK_STATE_REJECTED -> false;
        };
    }

    private static A2AProtocolException invalid(String message) {
        return new A2AProtocolException(A2AErrorCode.INVALID_AGENT_RESPONSE, message);
    }
}
