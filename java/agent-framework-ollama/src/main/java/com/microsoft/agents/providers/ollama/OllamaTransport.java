// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned transport boundary for Ollama chat.
 */
public interface OllamaTransport extends AutoCloseable {
    /** Executes one finite request. */
    CompletionStage<ChatResponse> completeAsync(
            ChatClientRequest request, OllamaChatClientOptions options, RunCancellation cancellation);

    /** Executes one streaming request. */
    Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, OllamaChatClientOptions options, RunCancellation cancellation);

    /** Releases owned transport resources. */
    @Override
    default void close() {}
}
