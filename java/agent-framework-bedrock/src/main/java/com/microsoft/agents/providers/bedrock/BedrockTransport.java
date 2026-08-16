// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned Bedrock transport boundary.
 */
public interface BedrockTransport extends AutoCloseable {
    /** Executes one finite Converse request. */
    CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation);

    /** Executes one ConverseStream request. */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, BedrockChatClientOptions options, RunCancellation cancellation);

    /** Releases owned transport resources. */
    @Override
    default void close() {}
}
