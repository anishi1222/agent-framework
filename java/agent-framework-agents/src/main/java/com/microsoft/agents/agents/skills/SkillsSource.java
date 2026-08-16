// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Discovers skills for one agent run.
 *
 * <p>Sources are trust boundaries. Callers must not expose untrusted skill content or executable
 * scripts without appropriate review, approval, and isolation.
 */
public interface SkillsSource {
    /**
     * Returns skills available to one run.
     *
     * @param context run and session context
     * @param cancellation cancellation signal
     * @return immutable skill-list stage
     */
    CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation);
}
