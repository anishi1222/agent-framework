// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2AErrorCode;
import com.microsoft.agents.protocols.a2a.A2AException;
import com.microsoft.agents.protocols.a2a.A2AProtocolException;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implements a bounded, process-memory task store with principal-isolated keys.
 *
 * <p>The store fails closed when full; it never evicts an active or terminal task implicitly.
 */
public final class InMemoryA2ATaskStore implements A2ATaskStore {
    private final int maxTasks;
    private final Map<TaskKey, Task> tasks = new LinkedHashMap<>();

    /**
     * Creates a bounded store.
     *
     * @param maxTasks maximum retained tasks across all principals
     */
    public InMemoryA2ATaskStore(int maxTasks) {
        this.maxTasks = HostingA2AValidation.positive(maxTasks, "maxTasks");
    }

    /** Creates a store retaining at most 10,000 tasks. */
    public InMemoryA2ATaskStore() {
        this(10_000);
    }

    @Override
    public synchronized CompletionStage<Task> createAsync(
            A2APrincipal principal, Task task) {
        TaskKey key = key(principal, task.id());
        Task existing = tasks.get(key);
        if (existing != null) {
            if (existing.equals(task)) {
                return CompletableFuture.completedFuture(existing);
            }
            return CompletableFuture.failedFuture(
                    new A2AException("A different task already uses the supplied task identifier."));
        }
        if (tasks.size() >= maxTasks) {
            return CompletableFuture.failedFuture(
                    new A2AException("In-memory A2A task capacity is exhausted."));
        }
        tasks.put(key, task);
        return CompletableFuture.completedFuture(task);
    }

    @Override
    public synchronized CompletionStage<Optional<Task>> getAsync(
            A2APrincipal principal, String taskId) {
        return CompletableFuture.completedFuture(
                Optional.ofNullable(tasks.get(key(principal, taskId))));
    }

    @Override
    public synchronized CompletionStage<Task> updateAsync(
            A2APrincipal principal, Task task, TaskState expectedState) {
        TaskKey key = key(principal, task.id());
        Task existing = tasks.get(key);
        if (existing == null) {
            return CompletableFuture.failedFuture(new A2AProtocolException(
                    A2AErrorCode.TASK_NOT_FOUND, "Task was not found."));
        }
        if (existing.status().state() != expectedState) {
            return CompletableFuture.failedFuture(
                    new A2AException("Task state changed concurrently."));
        }
        if (!existing.contextId().equals(task.contextId())) {
            return CompletableFuture.failedFuture(
                    new A2AException("Task contextId cannot change."));
        }
        tasks.put(key, task);
        return CompletableFuture.completedFuture(task);
    }

    @Override
    public synchronized CompletionStage<A2ACursorPage<Task>> listAsync(
            A2APrincipal principal, A2ARequests.ListTasks request) {
        ArrayList<Task> visible = new ArrayList<>();
        tasks.forEach((key, task) -> {
            if (key.matches(principal)
                    && (request.contextId() == null
                            || request.contextId().equals(task.contextId()))
                    && (request.status() == null
                            || request.status() == task.status().state())
                    && (request.statusTimestampAfter() == null
                            || task.status().timestamp().isAfter(
                                    request.statusTimestampAfter()))) {
                visible.add(project(task, request));
            }
        });
        visible.sort(Comparator.comparing(
                        (Task task) -> task.status().timestamp(), Comparator.reverseOrder())
                .thenComparing(Task::id));
        int offset = decodeCursor(request.pageToken(), request);
        if (offset > visible.size()) {
            return CompletableFuture.failedFuture(new A2AProtocolException(
                    A2AErrorCode.INVALID_PARAMS, "Task page token is outside the result set."));
        }
        int end = Math.min(visible.size(), offset + request.pageSize());
        List<Task> page = List.copyOf(visible.subList(offset, end));
        String next = end < visible.size() ? encodeCursor(end, request) : null;
        return CompletableFuture.completedFuture(
                new A2ACursorPage<>(page, next, request.pageSize(), (long) visible.size()));
    }

    private static Task project(Task task, A2ARequests.ListTasks request) {
        List<com.microsoft.agents.protocols.a2a.Message> history;
        if (request.historyLength() == 0) {
            history = List.of();
        } else {
            int from = Math.max(0, task.history().size() - request.historyLength());
            history = task.history().subList(from, task.history().size());
        }
        return new Task(
                task.id(),
                task.contextId(),
                task.status(),
                request.includeArtifacts() ? task.artifacts() : List.of(),
                history,
                task.metadata());
    }

    private static String encodeCursor(int offset, A2ARequests.ListTasks request) {
        String raw = "v1:" + offset + ":" + fingerprint(request);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(
            String token, A2ARequests.ListTasks request) {
        if (token == null) {
            return 0;
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3
                    || !"v1".equals(parts[0])
                    || !fingerprint(request).equals(parts[2])) {
                throw new IllegalArgumentException("cursor mismatch");
            }
            int offset = Integer.parseInt(parts[1]);
            if (offset < 0) {
                throw new IllegalArgumentException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException failure) {
            throw new A2AProtocolException(
                    A2AErrorCode.INVALID_PARAMS, "Task page token is invalid.", null, failure);
        }
    }

    private static String fingerprint(A2ARequests.ListTasks request) {
        return Integer.toUnsignedString(java.util.Objects.hash(
                        request.contextId(),
                        request.status(),
                        request.pageSize(),
                        request.historyLength(),
                        request.statusTimestampAfter(),
                        request.includeArtifacts(),
                        request.tenant()),
                16);
    }

    private static TaskKey key(A2APrincipal principal, String taskId) {
        HostingA2AValidation.required(principal, "principal");
        return new TaskKey(
                principal.principalId(),
                principal.isolationKey(),
                HostingA2AValidation.nonBlank(taskId, "taskId"));
    }

    private record TaskKey(String principalId, String isolationKey, String taskId) {
        private boolean matches(A2APrincipal principal) {
            return principalId.equals(principal.principalId())
                    && isolationKey.equals(principal.isolationKey());
        }
    }
}
