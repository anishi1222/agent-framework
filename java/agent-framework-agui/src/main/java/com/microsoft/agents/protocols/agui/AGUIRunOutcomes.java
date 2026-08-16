// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import java.util.List;

/** Contains the closed AG-UI run-finished outcome hierarchy. */
public final class AGUIRunOutcomes {
    private AGUIRunOutcomes() {}

    /** Closed marker implemented by every concrete run-finished outcome. */
    public sealed interface Outcome extends AGUIRunFinishedOutcome permits Success, Interrupt {}

    /** Represents a normal successful completion. */
    public record Success() implements Outcome {
        @Override
        public String type() {
            return "success";
        }
    }

    /**
     * Represents a terminal run that exposes one or more resumable interrupts.
     *
     * @param interrupts non-empty interrupts
     */
    public record Interrupt(List<AGUIInterrupt> interrupts) implements Outcome {
        /** Creates an immutable interrupt outcome. */
        public Interrupt {
            interrupts = AGUIValidation.list(interrupts, "interrupts");
            if (interrupts.isEmpty()) {
                throw AGUIValidation.invalid("Interrupt outcome requires at least one interrupt.");
            }
        }

        @Override
        public String type() {
            return "interrupt";
        }
    }
}
