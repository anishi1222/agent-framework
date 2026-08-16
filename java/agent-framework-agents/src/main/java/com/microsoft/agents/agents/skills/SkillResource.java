// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Supplies one named resource owned by a skill. */
public interface SkillResource {
    /**
     * Returns the case-insensitive lookup name.
     *
     * @return non-blank resource name
     */
    String name();

    /**
     * Returns the optional model-facing description.
     *
     * @return description, or {@code null}
     */
    String description();

    /**
     * Reads the resource.
     *
     * @param cancellation cancellation signal
     * @return resource content stage
     */
    CompletionStage<SkillResourceContent> readAsync(RunCancellation cancellation);
}
