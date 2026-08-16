// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Loads and appends chronologically ordered conversation messages.
 *
 * <p>History is contributed before caller input. Successful runs append caller input followed by
 * response messages. Failed runs do not append by default.
 */
public interface HistoryProvider extends ContextProvider {
    /**
     * Loads chronological history for a session.
     *
     * @param request immutable provider request
     * @return stage producing oldest-to-newest messages
     */
    CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request);

    /**
     * Appends chronological messages after a successful run.
     *
     * @param request immutable provider request
     * @param messages caller input followed by response messages
     * @return completion stage
     */
    CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages);

    @Override
    default CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        AgentValidation.requireNonNull(request, "request");
        CompletionStage<List<Message>> stage = loadMessagesAsync(request);
        if (stage == null) {
            return CompletableFuture.failedFuture(new com.microsoft.agents.core.AgentExecutionException(
                    "HistoryProvider.loadMessagesAsync returned null."));
        }
        return stage.thenApply(messages -> new ContextContribution(
                List.of(), AgentValidation.copyMessages(messages), java.util.Map.of(), List.of()));
    }

    @Override
    default CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
        AgentValidation.requireNonNull(completion, "completion");
        if (completion.failure() != null) {
            return CompletableFuture.completedFuture(null);
        }
        java.util.ArrayList<Message> additions = new java.util.ArrayList<>(completion.inputMessages());
        additions.addAll(completion.response().messages());
        CompletionStage<Void> stage = appendMessagesAsync(completion.request(), List.copyOf(additions));
        return stage == null
                ? CompletableFuture.failedFuture(new com.microsoft.agents.core.AgentExecutionException(
                        "HistoryProvider.appendMessagesAsync returned null."))
                : stage;
    }
}
