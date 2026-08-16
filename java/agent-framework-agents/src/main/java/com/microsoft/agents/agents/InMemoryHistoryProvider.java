// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stores chronological history inside the active {@link AgentSession} snapshot.
 *
 * <p>The provider itself is stateless and safe to share across sessions.
 */
public final class InMemoryHistoryProvider implements HistoryProvider {
    private final String id;

    /** Creates a provider with the stable identifier {@code history}. */
    public InMemoryHistoryProvider() {
        this("history");
    }

    /**
     * Creates a provider with an explicit stable identifier.
     *
     * @param id non-blank provider identifier
     */
    public InMemoryHistoryProvider(String id) {
        this.id = AgentValidation.requireNonBlank(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request) {
        AgentValidation.requireNonNull(request, "request");
        return CompletableFuture.completedFuture(request.session().messages());
    }

    @Override
    public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages) {
        AgentValidation.requireNonNull(request, "request");
        request.session().appendMessages(messages);
        return CompletableFuture.completedFuture(null);
    }
}
