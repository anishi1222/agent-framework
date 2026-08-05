// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Arrays;

/** Identifies which production state reader a raw rejection case targets. */
public enum SerializationDocumentKind {
    /** Agent-session state reader. */
    AGENT_SESSION("agent-session"),
    /** Workflow-checkpoint state reader. */
    WORKFLOW_CHECKPOINT("workflow-checkpoint");

    private final String wireName;

    SerializationDocumentKind(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the persisted discriminator.
     *
     * @return wire name
     */
    public String wireName() {
        return wireName;
    }

    static SerializationDocumentKind fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(kind -> kind.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() ->
                        new ConformanceValidationException("Unknown serialization document kind '" + wireName + "'."));
    }
}
