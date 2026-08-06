// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects how session-aware chat agents retain history during one orchestration run. */
public enum OrchestrationSessionPolicy {
    /** Invokes agents through their provider-neutral stateless run surface. */
    STATELESS,

    /** Uses one run-local session shared by session-aware participants. */
    SHARED,

    /** Uses one run-local session per session-aware participant. */
    ISOLATED
}
