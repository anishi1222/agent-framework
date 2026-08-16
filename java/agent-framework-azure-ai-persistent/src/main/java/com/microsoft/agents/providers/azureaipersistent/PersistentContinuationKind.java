// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/** Identifies a caller-driven persistent run continuation. */
public enum PersistentContinuationKind {
    /** Submit function tool outputs. */
    TOOL_OUTPUTS,
    /** Approve or reject caller-reviewed tool outputs. */
    APPROVAL,
    /** Supply additional user input; unsupported by the pinned service SDK. */
    INPUT
}
