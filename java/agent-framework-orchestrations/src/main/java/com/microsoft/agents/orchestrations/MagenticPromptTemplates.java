// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Holds injectable framework-owned prompts for an agent-backed Magentic manager.
 *
 * <p>Templates support the literal placeholders {@code {task}}, {@code {team}}, and
 * {@code {ledger}}. Replacement is literal; unrelated braces are retained.
 *
 * @param planningPrompt initial planning prompt
 * @param replanningPrompt stalled-run replanning prompt
 * @param assessmentPrompt progress-assessment prompt
 * @param finalAnswerPrompt final answer prompt
 */
public record MagenticPromptTemplates(
        String planningPrompt, String replanningPrompt, String assessmentPrompt, String finalAnswerPrompt) {
    /** Creates validated immutable prompt templates. */
    public MagenticPromptTemplates {
        planningPrompt = OrchestrationValidation.requireText(planningPrompt, "planningPrompt");
        replanningPrompt = OrchestrationValidation.requireText(replanningPrompt, "replanningPrompt");
        assessmentPrompt = OrchestrationValidation.requireText(assessmentPrompt, "assessmentPrompt");
        finalAnswerPrompt = OrchestrationValidation.requireText(finalAnswerPrompt, "finalAnswerPrompt");
    }

    /**
     * Returns the provider-neutral framework defaults.
     *
     * @return default prompt templates
     */
    public static MagenticPromptTemplates defaults() {
        return new MagenticPromptTemplates("""
                Create a concise task plan for the request below. Assign every task to exactly one registered
                participant. Return a structured MagenticPlan value and no provider-specific control data.

                Request:
                {task}

                Registered participants:
                {team}
                """, """
                Replace the stalled plan with a materially different plan. Preserve useful completed work,
                assign only registered participants, and return a structured MagenticPlan value.

                Request:
                {task}

                Registered participants:
                {team}

                Current ledger:
                {ledger}
                """, """
                Assess whether the request is satisfied, whether the latest turn made progress, whether execution
                is stalled, and who should act next. Return a structured MagenticProgressAssessment value. Any
                next participant must be one of the registered identifiers.

                Request:
                {task}

                Registered participants:
                {team}

                Current ledger:
                {ledger}
                """, """
                Synthesize the final answer to the original request from the deterministic ledger and transcript.
                Address the user directly and do not discuss internal orchestration mechanics.

                Request:
                {task}

                Current ledger:
                {ledger}
                """);
    }
}
