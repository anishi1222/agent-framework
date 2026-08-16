// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Identifies protocol-neutral hosted stream events. */
public enum HostingEventType {
    /** A run was accepted and assigned an identifier. */
    RUN_STARTED("run-started"),
    /** An agent emitted one framework response update. */
    AGENT_UPDATE("agent-update"),
    /** A workflow emitted one sequenced lifecycle event. */
    WORKFLOW_EVENT("workflow-event"),
    /** An orchestration emitted one deterministically sequenced lifecycle event. */
    ORCHESTRATION_EVENT("orchestration-event");

    private final String value;

    HostingEventType(String value) {
        this.value = value;
    }

    /**
     * Returns the stable wire value.
     *
     * @return wire value
     */
    public String value() {
        return value;
    }
}
