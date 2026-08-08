// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.SendMessageRequest;
import com.microsoft.agents.protocols.a2a.Task;
import java.util.Objects;

/**
 * Describes one principal-isolated framework execution.
 *
 * @param principal authenticated principal
 * @param request message request
 * @param task current task
 * @param streaming whether incremental artifacts are requested
 * @param continuation whether the message continues an interrupted task
 */
public record A2AExecutionContext(
        A2APrincipal principal,
        SendMessageRequest request,
        Task task,
        boolean streaming,
        boolean continuation) {
    /** Creates a validated execution context. */
    public A2AExecutionContext {
        principal = Objects.requireNonNull(principal, "principal");
        request = Objects.requireNonNull(request, "request");
        task = Objects.requireNonNull(task, "task");
    }
}
