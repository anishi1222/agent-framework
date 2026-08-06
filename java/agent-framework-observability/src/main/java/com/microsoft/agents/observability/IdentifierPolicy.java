// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

/** Controls recording of session, response, call, invocation, and workflow-run identifiers. */
public enum IdentifierPolicy {
    /** Omits identifiers, which is the default. */
    OMIT,
    /** Records a deterministic lowercase SHA-256 digest instead of the source identifier. */
    HASH,
    /** Records the sanitized source identifier. */
    PLAIN
}
