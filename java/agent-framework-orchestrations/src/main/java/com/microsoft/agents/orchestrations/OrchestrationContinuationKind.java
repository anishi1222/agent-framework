// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Identifies the input needed to continue a suspended orchestration. */
public enum OrchestrationContinuationKind {
    /** A participant requires tool-approval decisions. */
    APPROVAL,

    /** A handoff or manager requires additional human messages. */
    HUMAN_INPUT,

    /** A Magentic plan requires explicit review. */
    PLAN_REVIEW
}
