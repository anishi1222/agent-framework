// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Reports a failed, canceled, or rejected remote task. */
public final class A2ARemoteTaskException extends A2AException {
    private static final long serialVersionUID = 1L;

    private final transient Task task;

    /**
     * Creates a remote-task failure.
     *
     * @param task terminal task
     */
    public A2ARemoteTaskException(Task task) {
        super("Remote A2A task " + task.id() + " ended in " + task.status().state() + ".");
        this.task = task;
    }

    /** Returns the terminal task snapshot. */
    public Task task() {
        return task;
    }
}
