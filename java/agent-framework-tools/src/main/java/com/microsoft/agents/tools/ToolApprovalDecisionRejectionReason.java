// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Classifies why an approval decision was not accepted.
 */
public enum ToolApprovalDecisionRejectionReason {
    /** The approval identifier is unknown to this uninterrupted logical run. */
    STALE_APPROVAL,
    /** Invocation or request digest does not match the issued request. */
    MISMATCHED_REQUEST,
    /** A decision was already accepted in the current resume operation. */
    DECISION_ALREADY_PENDING,
    /** The authority was already applied and consumed. */
    AUTHORITY_CONSUMED
}
