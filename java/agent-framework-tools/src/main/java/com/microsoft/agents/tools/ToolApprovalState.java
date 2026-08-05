// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Classifies the immutable state carried by an approval request or decision.
 */
public enum ToolApprovalState {
    /** No decision has consumed the approval authority. */
    PENDING,
    /** The decision authorizes exactly one invocation. */
    APPROVED,
    /** The decision rejects invocation. */
    REJECTED
}
