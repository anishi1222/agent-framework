// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponse;
import java.util.concurrent.CompletionStage;

/** Continues one finite chat middleware pipeline. */
@FunctionalInterface
public interface ChatMiddlewareNext {
    /**
     * Continues execution at most once.
     *
     * @param context immutable chat context
     * @return response stage
     */
    CompletionStage<ChatResponse> invokeAsync(ChatMiddlewareContext context);
}
