// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Stores task snapshots under authenticated principal and isolation dimensions. */
public interface A2ATaskStore {
    /**
     * Creates a task for one principal.
     *
     * @param principal authenticated principal
     * @param task initial task
     * @return created task
     */
    CompletionStage<Task> createAsync(A2APrincipal principal, Task task);

    /**
     * Loads one visible task.
     *
     * @param principal authenticated principal
     * @param taskId task identifier
     * @return optional detached task
     */
    CompletionStage<Optional<Task>> getAsync(A2APrincipal principal, String taskId);

    /**
     * Replaces one task only when its current state matches.
     *
     * @param principal authenticated principal
     * @param task replacement task
     * @param expectedState expected current state
     * @return replacement task
     */
    CompletionStage<Task> updateAsync(
            A2APrincipal principal, Task task, TaskState expectedState);

    /**
     * Lists visible tasks with filters and cursor pagination.
     *
     * @param principal authenticated principal
     * @param request list request
     * @return page
     */
    CompletionStage<A2ACursorPage<Task>> listAsync(
            A2APrincipal principal, A2ARequests.ListTasks request);
}
