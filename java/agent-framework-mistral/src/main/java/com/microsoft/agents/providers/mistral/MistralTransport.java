// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned transport boundary for Mistral-compatible chat endpoints.
 */
public interface MistralTransport extends AutoCloseable {
    /**
     * Executes one finite request.
     *
     * @param request immutable framework request
     * @param options immutable provider options
     * @param cancellation explicit cancellation signal
     * @return complete response stage
     */
    CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, MistralChatClientOptions options, RunCancellation cancellation);

    /**
     * Executes one streaming request.
     *
     * @param request immutable framework request
     * @param options immutable provider options
     * @param cancellation explicit cancellation signal
     * @return cold, single-subscriber update publisher
     */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, MistralChatClientOptions options, RunCancellation cancellation);

    /** Releases resources owned by the transport. */
    @Override
    default void close() {}
}
