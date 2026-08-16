// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/** Identifies one supported declarative workflow edge shape. */
public enum DeclarativeEdgeKind {
    /** Routes every source output to one target. */
    DIRECT,

    /** Routes a source output when a caller-registered typed condition accepts it. */
    CONDITIONAL,

    /** Routes one source output to multiple targets. */
    FAN_OUT,

    /** Releases one fan-in input after all required sources arrive. */
    FAN_IN
}
