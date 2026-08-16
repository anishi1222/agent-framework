// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import java.util.List;

/** Transforms one successful sequential participant output into the next participant input. */
@FunctionalInterface
public interface SequentialInputTransform {
    /**
     * Produces ordered input for the next participant.
     *
     * @param context immutable pipeline state
     * @return non-null ordered messages
     */
    List<Message> transform(SequentialTransformContext context);
}
