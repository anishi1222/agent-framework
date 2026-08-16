// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned Anthropic transport boundary.
 */
public interface AnthropicTransport extends AutoCloseable {
    /** Executes one finite Messages request. */
    CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, AnthropicChatClientOptions options, RunCancellation cancellation);

    /** Executes one streaming Messages request. */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, AnthropicChatClientOptions options, RunCancellation cancellation);

    /** Releases owned resources. */
    @Override
    default void close() {}
}
