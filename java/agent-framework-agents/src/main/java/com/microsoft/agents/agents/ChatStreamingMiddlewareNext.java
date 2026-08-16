// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponseUpdate;
import java.util.concurrent.Flow;

/** Continues one streaming chat middleware pipeline. */
@FunctionalInterface
public interface ChatStreamingMiddlewareNext {
    /**
     * Continues execution at most once.
     *
     * @param context immutable chat context
     * @return update publisher
     */
    Flow.Publisher<ChatResponseUpdate> invokeStreaming(ChatMiddlewareContext context);
}
