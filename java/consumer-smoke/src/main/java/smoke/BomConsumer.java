// Copyright (c) Microsoft. All rights reserved.

package smoke;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.harness.HarnessAgentOptions;
import com.microsoft.agents.harness.files.InMemoryAgentFileStore;
import com.microsoft.agents.workflows.FunctionalWorkflow;
import com.microsoft.agents.workflows.FunctionalWorkflowAgent;
import com.microsoft.agents.workflows.WorkflowCodecs;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Verifies that a Java 25 application can consume the published BOM and artifacts. */
public final class BomConsumer {
    private BomConsumer() {}

    /**
     * Runs the publication smoke test.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        HarnessAgentOptions.builder()
                .fileMemoryStore(new InMemoryAgentFileStore())
                .build();
        var codec = WorkflowCodecs.stringCodec();
        try (var workflow = FunctionalWorkflow.builder(
                                "bom-smoke", String.class, String.class, codec, codec)
                        .body((input, context) ->
                                CompletableFuture.completedFuture(input.toUpperCase(Locale.ROOT)))
                        .build();
                var agent = workflow.asAgent(
                        new AgentMetadata("bom-smoke-agent", null, null),
                        FunctionalWorkflowAgent.joinedTextInput(),
                        FunctionalWorkflowAgent.assistantTextOutput())) {
            var response = agent.run("published");
            if (!"PUBLISHED".equals(response.value()) || !"PUBLISHED".equals(response.text())) {
                throw new IllegalStateException("Unexpected functional workflow agent response.");
            }
        }
    }
}
