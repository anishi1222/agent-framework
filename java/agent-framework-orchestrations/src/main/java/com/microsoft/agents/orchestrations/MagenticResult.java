// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import java.util.Objects;

/**
 * Carries the optional synthesized answer and final immutable Magentic ledger.
 *
 * @param response optional synthesized answer
 * @param ledger final ledger snapshot
 */
public record MagenticResult(AgentResponse<?> response, MagenticLedger ledger) {
    /** Creates a validated immutable result. */
    public MagenticResult {
        ledger = Objects.requireNonNull(ledger, "ledger");
    }
}
