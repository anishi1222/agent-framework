// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Defines one progressively disclosed agent skill. */
public interface Skill {
    /**
     * Returns level-one discovery metadata.
     *
     * @return immutable frontmatter
     */
    SkillFrontmatter frontmatter();

    /**
     * Loads the full skill instructions.
     *
     * @param cancellation cancellation signal
     * @return full skill content stage
     */
    CompletionStage<String> contentAsync(RunCancellation cancellation);

    /**
     * Resolves a resource by case-insensitive name.
     *
     * @param name resource name
     * @param cancellation cancellation signal
     * @return resource stage, producing {@code null} when absent
     */
    CompletionStage<SkillResource> resourceAsync(String name, RunCancellation cancellation);

    /**
     * Resolves a script by case-insensitive name.
     *
     * @param name script name
     * @param cancellation cancellation signal
     * @return script stage, producing {@code null} when absent
     */
    CompletionStage<SkillScript> scriptAsync(String name, RunCancellation cancellation);
}
