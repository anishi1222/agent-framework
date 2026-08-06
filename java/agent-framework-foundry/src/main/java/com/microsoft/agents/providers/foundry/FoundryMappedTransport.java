// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.providers.openai.OpenAITransport;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class FoundryMappedTransport implements FoundryTransport {
    private final FoundryChatClientOptions options;

    private final FoundryTransport delegate;

    FoundryMappedTransport(FoundryChatClientOptions options, FoundryTransport delegate) {
        this.options = Objects.requireNonNull(options, "options");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public CompletionStage<OpenAITransport.Response> completeAsync(
            OpenAITransport.Request request, RunCancellation cancellation) {
        return delegate.completeAsync(map(request), cancellation);
    }

    @Override
    public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
            OpenAITransport.Request request, RunCancellation cancellation) {
        return delegate.completeStreaming(map(request), cancellation);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private OpenAITransport.Request map(OpenAITransport.Request request) {
        String continuation =
                request.conversationId() == null ? request.previousResponseId() : request.conversationId();
        String previousResponseId =
                options.continuationMode() == FoundryContinuationMode.PREVIOUS_RESPONSE ? continuation : null;
        String conversationId =
                options.continuationMode() == FoundryContinuationMode.CONVERSATION ? continuation : null;
        boolean agent = options.surface() == FoundrySurface.AGENT;
        return new OpenAITransport.Request(
                request.model(),
                request.input(),
                agent ? null : request.instructions(),
                agent ? null : request.temperature(),
                agent ? null : request.topP(),
                request.maxOutputTokens(),
                agent ? List.of() : request.tools(),
                agent ? null : request.toolChoice(),
                agent ? null : request.parallelToolCalls(),
                request.user(),
                request.store(),
                previousResponseId,
                conversationId,
                request.metadata(),
                request.responseOptions());
    }
}
