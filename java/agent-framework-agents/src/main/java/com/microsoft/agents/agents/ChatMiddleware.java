// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Intercepts finite and streaming chat-client calls. */
public interface ChatMiddleware {
    /**
     * Intercepts a finite chat call.
     *
     * @param context immutable invocation context
     * @param next single-use continuation
     * @return response stage
     */
    default CompletionStage<ChatResponse> invokeAsync(ChatMiddlewareContext context, ChatMiddlewareNext next) {
        return next.invokeAsync(context);
    }

    /**
     * Intercepts a streaming chat call.
     *
     * @param context immutable invocation context
     * @param next single-use continuation
     * @return update publisher
     */
    default Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            ChatMiddlewareContext context, ChatStreamingMiddlewareNext next) {
        return next.invokeStreaming(context);
    }
}
