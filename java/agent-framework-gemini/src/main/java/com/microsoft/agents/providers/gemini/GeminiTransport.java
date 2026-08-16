// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned Gemini transport boundary.
 */
public interface GeminiTransport extends AutoCloseable {
    /** Executes one finite generate-content request. */
    CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, GeminiChatClientOptions options, RunCancellation cancellation);

    /** Executes one streaming generate-content request. */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, GeminiChatClientOptions options, RunCancellation cancellation);

    /** Releases owned resources. */
    @Override
    default void close() {}
}
