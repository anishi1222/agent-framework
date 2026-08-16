// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Current experimental feature identifiers mirrored from the Python implementation.
 *
 * <p>This is a stage-scoped inventory rather than a permanent compatibility surface. Constants may
 * move or disappear as features graduate.
 */
public enum ExperimentalFeature {
    /** Declarative agent definitions. */
    DECLARATIVE_AGENTS,
    /** Provider-neutral evaluations. */
    EVALS,
    /** File-backed history. */
    FILE_HISTORY,
    /** FIDES information-flow security. */
    FIDES,
    /** Foundry tools. */
    FOUNDRY_TOOLS,
    /** Foundry preview tools. */
    FOUNDRY_PREVIEW_TOOLS,
    /** Functional workflows. */
    FUNCTIONAL_WORKFLOWS,
    /** Harness APIs. */
    HARNESS,
    /** MCP long-running tasks. */
    MCP_LONG_RUNNING_TASKS,
    /** MCP skills. */
    MCP_SKILLS,
    /** Progressive tool disclosure. */
    PROGRESSIVE_TOOLS,
    /** Session-store APIs. */
    SESSION_STORE,
    /** Prompt conversion for agents. */
    TO_PROMPT_AGENT;

    /**
     * Returns the annotation-ready feature identifier.
     *
     * @return stable identifier for the current stage
     */
    public String id() {
        return name();
    }
}
