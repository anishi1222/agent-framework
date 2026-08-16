// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOrchestrationCodecs;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.orchestrations.OrchestrationParticipant;
import com.microsoft.agents.orchestrations.SequentialOrchestration;
import com.microsoft.agents.protocols.agui.AGUIClient;
import com.microsoft.agents.protocols.agui.AGUIClientOptions;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEventType;
import com.microsoft.agents.protocols.agui.AGUIEvents;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUILimits;
import com.microsoft.agents.protocols.agui.AGUIMessage;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.AGUIResumeEntry;
import com.microsoft.agents.protocols.agui.AGUIResumeStatus;
import com.microsoft.agents.protocols.agui.AGUIRunOutcomes;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AGUIOrchestrationResumeRuntimeTest {
    @Test
    void orchestration_shouldStreamInterruptAndResumeSuspendedProductionState() throws Exception {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient()
                .enqueue(approvalResponse())
                .enqueue(AGUIHostingTestSupport.response("continued"));
        HostingLimits hostingLimits = HostingLimits.defaults();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        try (ChatAgent participant = new ChatAgent(
                        transport,
                        new AgentMetadata("participant", "participant", null),
                        ChatOptions.empty(),
                        List.of(tool));
                SequentialOrchestration orchestration = SequentialOrchestration.builder(
                                List.of(OrchestrationParticipant.of(participant)))
                        .id("approval-orchestration")
                        .build();
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(8, Duration.ofMinutes(5))) {
            routes.registerOrchestration(
                    "/ag-ui/orchestration-approval",
                    "approval-orchestration",
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
                    AGUIClient client = new AGUIClient(
                            AGUIClientOptions.builder(resolve(server.endpoint(), "/ag-ui/orchestration-approval"))
                                    .allowInsecureLoopback()
                                    .limits(aguiLimits)
                                    .build())) {
                client.capabilitiesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);

                // Act
                List<AGUIEvent> first = client.runAsync(input("run-1", initialMessages(), List.of()))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                AGUIEvents.RunFinished interrupted = first.stream()
                        .filter(AGUIEvents.RunFinished.class::isInstance)
                        .map(AGUIEvents.RunFinished.class::cast)
                        .findFirst()
                        .orElseThrow();
                String interruptId = ((AGUIRunOutcomes.Interrupt) interrupted.outcome())
                        .interrupts()
                        .getFirst()
                        .id();
                List<AGUIMessage> resumeMessages = List.of(
                        initialMessages().getFirst(),
                        new AGUIMessages.Assistant(
                                "assistant-call",
                                null,
                                null,
                                null,
                                List.of(new AGUIMessages.ToolCall(
                                        "call-approval", new AGUIMessages.FunctionCall("write", "{}"), null))));
                List<AGUIEvent> resumed = collect(client.resumeStreaming(input(
                                "run-2",
                                resumeMessages,
                                List.of(new AGUIResumeEntry(
                                        interruptId,
                                        AGUIResumeStatus.RESOLVED,
                                        StateValue.object(Map.of("approved", StateValue.bool(true))))))))
                        .get(5, TimeUnit.SECONDS);

                // Assert
                assertThat(first)
                        .extracting(AGUIEvent::type)
                        .contains(
                                AGUIEventType.TOOL_CALL_START, AGUIEventType.TOOL_CALL_END, AGUIEventType.RUN_FINISHED);
                assertThat(resumed)
                        .extracting(AGUIEvent::type)
                        .containsExactly(AGUIEventType.RUN_STARTED, AGUIEventType.RUN_FINISHED);
                assertThat(invocations).hasValue(1);
                assertThat(dispatcher.continuationCount()).isZero();
            }
        }
    }

    private static CompletableFuture<List<AGUIEvent>> collect(Flow.Publisher<AGUIEvent> publisher) {
        CompletableFuture<List<AGUIEvent>> result = new CompletableFuture<>();
        ArrayList<AGUIEvent> events = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AGUIEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(events));
            }
        });
        return result;
    }

    private static FunctionTool approvalTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "Writes data",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
    }

    private static ChatResponse approvalResponse() {
        FunctionCallContent call = new FunctionCallContent("call-approval", "write", StateValue.object(Map.of()));
        return ChatResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                .finishReason(FinishReason.TOOL_CALLS)
                .build();
    }

    private static List<AGUIMessage> initialMessages() {
        return List.of(new AGUIMessages.User("user", new AGUIMessages.TextUserContent("write"), null, null));
    }

    private static RunAgentInput input(String runId, List<AGUIMessage> messages, List<AGUIResumeEntry> resume) {
        return new RunAgentInput(
                "thread",
                runId,
                null,
                StateValue.object(Map.of()),
                messages,
                List.of(),
                List.of(),
                StateValue.object(Map.of()),
                resume);
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
