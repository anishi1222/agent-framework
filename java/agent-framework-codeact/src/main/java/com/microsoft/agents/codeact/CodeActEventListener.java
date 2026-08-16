// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

/** Observes immutable CodeAct lifecycle events in deterministic sequence order. */
@FunctionalInterface
public interface CodeActEventListener {
    /**
     * Observes one event.
     *
     * @param event immutable lifecycle event
     */
    void onEvent(CodeActEvent event);
}
