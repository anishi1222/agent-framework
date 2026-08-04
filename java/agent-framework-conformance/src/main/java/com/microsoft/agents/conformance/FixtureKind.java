// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Arrays;

/**
 * Identifies the explicit schema used by a conformance fixture.
 */
public enum FixtureKind {
    /** A generic API or observable-behavior contract. */
    CONTRACT("contract"),
    /** Message roles and content variants. */
    MESSAGE_CONTENT("message-content"),
    /** Response-update aggregation behavior. */
    RESPONSE_AGGREGATION("response-aggregation"),
    /** Tool-call loop events and outcomes. */
    TOOL_LOOP("tool-loop"),
    /** Cancellation, demand, and terminal run signals. */
    RUN_SIGNAL("run-signal"),
    /** Java versioned session envelope and store operations. */
    SESSION_SNAPSHOT("session-snapshot"),
    /** Workflow execution and checkpoint event history. */
    WORKFLOW_TRACE("workflow-trace");

    private final String wireName;

    FixtureKind(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the stable JSON discriminator.
     *
     * @return fixture kind discriminator
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolves a stable JSON discriminator.
     *
     * @param wireName fixture kind discriminator
     * @return matching fixture kind
     * @throws ConformanceValidationException when the discriminator is unknown
     */
    public static FixtureKind fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new ConformanceValidationException("Unknown fixture kind '" + wireName + "'."));
    }
}
