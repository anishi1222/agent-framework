// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

interface PersistentTransport {
    CompletionStage<PersistentAgentDefinition> createAgentAsync(
            PersistentAgentCreateRequest request, RunCancellation cancellation);

    CompletionStage<PersistentAgentDefinition> getAgentAsync(String agentId, RunCancellation cancellation);

    CompletionStage<PersistentAgentDefinition> updateAgentAsync(
            String agentId, PersistentAgentCreateRequest request, RunCancellation cancellation);

    CompletionStage<Void> deleteAgentAsync(String agentId, RunCancellation cancellation);

    CompletionStage<PersistentPage<PersistentAgentDefinition>> listAgentsAsync(
            int limit, String after, RunCancellation cancellation);

    CompletionStage<PersistentThread> createThreadAsync(Map<String, String> metadata, RunCancellation cancellation);

    CompletionStage<PersistentThread> getThreadAsync(String threadId, RunCancellation cancellation);

    CompletionStage<Void> deleteThreadAsync(String threadId, RunCancellation cancellation);

    CompletionStage<PersistentMessage> createMessageAsync(
            String threadId,
            com.microsoft.agents.core.Role role,
            String text,
            List<PersistentAttachment> attachments,
            Map<String, String> metadata,
            RunCancellation cancellation);

    CompletionStage<PersistentPage<PersistentMessage>> listMessagesAsync(
            String threadId, String runId, int limit, String after, RunCancellation cancellation);

    CompletionStage<PersistentRun> createRunAsync(PersistentRunRequest request, RunCancellation cancellation);

    CompletionStage<PersistentRun> getRunAsync(String threadId, String runId, RunCancellation cancellation);

    CompletionStage<PersistentPage<PersistentRun>> listRunsAsync(
            String threadId, int limit, String after, RunCancellation cancellation);

    CompletionStage<PersistentRun> cancelRunAsync(String threadId, String runId, RunCancellation cancellation);

    CompletionStage<PersistentRun> submitToolOutputsAsync(
            String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation);

    Flow.Publisher<PersistentRunEvent> createRunStreaming(PersistentRunRequest request, RunCancellation cancellation);
}
