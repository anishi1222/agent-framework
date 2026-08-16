// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Represents the success-or-interrupt union on a current AG-UI {@code RUN_FINISHED}. */
public sealed interface AGUIRunFinishedOutcome permits AGUIRunOutcomes.Outcome {
    /**
     * Returns the exact lower-case outcome discriminator.
     *
     * @return {@code success} or {@code interrupt}
     */
    String type();
}
