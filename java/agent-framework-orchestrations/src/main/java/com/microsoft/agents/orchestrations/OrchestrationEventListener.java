// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Observes orchestration events without introducing an observability-module dependency.
 *
 * <p>Listeners must return quickly and must not throw. Listener failures are isolated from the run
 * so optional instrumentation cannot change application behavior.
 */
@FunctionalInterface
public interface OrchestrationEventListener {
    /**
     * Observes one event in deterministic sequence order.
     *
     * @param event event to observe
     */
    void onEvent(OrchestrationEvent event);
}
