// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOrchestrationCodecs;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingWorkflowCodecs;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.orchestrations.OrchestrationParticipant;
import com.microsoft.agents.orchestrations.SequentialOrchestration;
import com.microsoft.agents.protocols.agui.AGUIClient;
import com.microsoft.agents.protocols.agui.AGUIClientOptions;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEventType;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUILimits;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AGUIWorkflowOrchestrationRuntimeTest {
    @Test
    void workflowAndOrchestration_shouldUseProductionStreamsAndDeterministicEvents() throws Exception {
        // Arrange
        HostingLimits hostingLimits = HostingLimits.defaults();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        Workflow<String, String> workflow = workflow();
        AGUIHostingTestSupport.ScriptedChatClient firstClient =
                new AGUIHostingTestSupport.ScriptedChatClient().enqueue(AGUIHostingTestSupport.response("first"));
        AGUIHostingTestSupport.ScriptedChatClient secondClient =
                new AGUIHostingTestSupport.ScriptedChatClient().enqueue(AGUIHostingTestSupport.response("second"));
        try (ChatAgent first = AGUIHostingTestSupport.chatAgent("first", firstClient);
                ChatAgent second = AGUIHostingTestSupport.chatAgent("second", secondClient);
                SequentialOrchestration orchestration = SequentialOrchestration.builder(
                                List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)))
                        .id("sequence")
                        .build();
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(16, Duration.ofMinutes(5))) {
            routes.registerWorkflow("/ag-ui/workflow", "workflow", workflow, HostingWorkflowCodecs.text());
            routes.registerOrchestration(
                    "/ag-ui/orchestration",
                    "sequence",
                    orchestration,
                    HostingOrchestrationCodecs.of(response -> StateValue.string(response.text())));
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher,
                    routes,
                    threads,
                    HostingHttpServerOptions.builder().limits(hostingLimits).build(),
                    AGUIHostingOptions.defaults(),
                    new AGUIJsonCodec(aguiLimits));
            try (AGUIHttpServer server = AGUIHttpServer.start(handler);
                    AGUIClient workflowClient = client(server, "/ag-ui/workflow", aguiLimits);
                    AGUIClient orchestrationClient = client(server, "/ag-ui/orchestration", aguiLimits)) {
                // Act
                List<AGUIEvent> workflowEvents = workflowClient
                        .runAsync(input("workflow-thread", "workflow-run", "hello"))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                List<AGUIEvent> orchestrationEvents = orchestrationClient
                        .runAsync(input("orchestration-thread", "orchestration-run", "request"))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                // Assert
                assertThat(workflowEvents)
                        .extracting(AGUIEvent::type)
                        .startsWith(AGUIEventType.RUN_STARTED)
                        .contains(AGUIEventType.STEP_STARTED, AGUIEventType.STEP_FINISHED)
                        .endsWith(AGUIEventType.RUN_FINISHED);
                assertThat(orchestrationEvents)
                        .extracting(AGUIEvent::type)
                        .startsWith(AGUIEventType.RUN_STARTED)
                        .contains(AGUIEventType.CUSTOM)
                        .endsWith(AGUIEventType.RUN_FINISHED);
                assertThat(orchestrationEvents.getLast())
                        .isInstanceOf(com.microsoft.agents.protocols.agui.AGUIEvents.RunFinished.class);
                assertThat(dispatcher.activeRunCount()).isZero();
            }
        } finally {
            workflow.close();
        }
    }

    private static Workflow<String, String> workflow() {
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("workflow", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "process", FunctionExecutor.sync(String.class, String.class, (value, context) -> value + "-workflow"));
        return builder.entry(node).output(node).build();
    }

    private static AGUIClient client(AGUIHttpServer server, String path, AGUILimits limits) {
        return new AGUIClient(AGUIClientOptions.builder(resolve(server.endpoint(), path))
                .allowInsecureLoopback()
                .limits(limits)
                .build());
    }

    private static RunAgentInput input(String thread, String run, String text) {
        return new RunAgentInput(
                thread,
                run,
                StateValue.object(Map.of()),
                List.of(new AGUIMessages.User("user-" + run, new AGUIMessages.TextUserContent(text), null, null)),
                List.of(),
                List.of(),
                StateValue.object(Map.of()));
    }

    private static AGUILimits limits(HostingLimits value) {
        return new AGUILimits(
                value.maxRequestBytes(),
                value.maxResponseBytes(),
                value.maxNestingDepth(),
                value.maxStringLength(),
                value.maxNumericTokenLength(),
                value.maxCollectionEntries(),
                1_000,
                value.maxWebSocketFrameBytes(),
                value.maxEventsPerRun(),
                value.maxSseBufferedEvents());
    }

    private static URI resolve(URI origin, String path) {
        return URI.create(origin.getScheme() + "://" + origin.getAuthority() + path);
    }
}
