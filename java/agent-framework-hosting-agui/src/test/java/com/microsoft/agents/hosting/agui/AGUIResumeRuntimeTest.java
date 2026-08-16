// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AGUIResumeRuntimeTest {
    @Test
    void approvalInterrupt_shouldBePrincipalBoundOpaqueOneTimeAndResumeWithoutReplay() throws Exception {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        FunctionCallContent call = new FunctionCallContent("call-approval", "write", StateValue.object(Map.of()));
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> AGUIHostingTestSupport.publisher(
                        List.of(AGUIHostingTestSupport.update(
                                0, "assistant-call", List.of(call), FinishReason.TOOL_CALLS)),
                        0,
                        null))
                .enqueue(AGUIHostingTestSupport.response("done"));
        HostingLimits hostingLimits =
                HostingLimits.builder().continuationTtl(Duration.ofMinutes(2)).build();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        HostingAuthenticator authenticator = request -> {
            String principalHeader = request.firstHeader("x-test-principal");
            String principal = principalHeader == null ? "anonymous" : principalHeader;
            return CompletableFuture.completedFuture(
                    HostingAuthentication.authenticated(new HostingPrincipal(principal, principal)));
        };
        try (ChatAgent agent = new ChatAgent(
                        transport,
                        new AgentMetadata("approval", "approval", null),
                        ChatOptions.empty(),
                        List.of(tool));
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(8, Duration.ofMinutes(5))) {
            routes.registerAgent("/ag-ui/approval", agent);
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher,
                    routes,
                    threads,
                    HostingHttpServerOptions.builder()
                            .limits(hostingLimits)
                            .authenticator(authenticator)
                            .build(),
                    AGUIHostingOptions.defaults(),
                    new AGUIJsonCodec(aguiLimits));
            try (AGUIHttpServer server = AGUIHttpServer.start(handler);
                    AGUIClient client =
                            new AGUIClient(AGUIClientOptions.builder(resolve(server.endpoint(), "/ag-ui/approval"))
                                    .allowInsecureLoopback()
                                    .limits(aguiLimits)
                                    .header("X-Test-Principal", "alice")
                                    .build());
                    AGUIClient otherPrincipal =
                            new AGUIClient(AGUIClientOptions.builder(resolve(server.endpoint(), "/ag-ui/approval"))
                                    .allowInsecureLoopback()
                                    .limits(aguiLimits)
                                    .header("X-Test-Principal", "bob")
                                    .build())) {
                client.capabilitiesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
                otherPrincipal.capabilitiesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
                RunAgentInput firstInput = input("run-1", initialMessages(), List.of());

                // Act
                List<AGUIEvent> first =
                        client.runAsync(firstInput).toCompletableFuture().get(5, TimeUnit.SECONDS);
                AGUIEvents.RunFinished interrupted = first.stream()
                        .filter(AGUIEvents.RunFinished.class::isInstance)
                        .map(AGUIEvents.RunFinished.class::cast)
                        .findFirst()
                        .orElseThrow();
                AGUIRunOutcomes.Interrupt outcome = (AGUIRunOutcomes.Interrupt) interrupted.outcome();
                String interruptId = outcome.interrupts().getFirst().id();
                RunAgentInput resume = input(
                        "run-2",
                        List.of(
                                initialMessages().getFirst(),
                                new AGUIMessages.Assistant(
                                        "assistant-call",
                                        null,
                                        null,
                                        null,
                                        List.of(new AGUIMessages.ToolCall(
                                                "call-approval", new AGUIMessages.FunctionCall("write", "{}"), null)))),
                        List.of(new AGUIResumeEntry(
                                interruptId,
                                AGUIResumeStatus.RESOLVED,
                                StateValue.object(Map.of("approved", StateValue.bool(true))))));

                assertThatThrownBy(() ->
                                collect(otherPrincipal.resumeStreaming(resume)).get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(com.microsoft.agents.protocols.agui.AGUIProtocolException.class);
                List<AGUIEvent> resumed = collect(client.resumeStreaming(resume))
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);

                // Assert
                assertThat(first).extracting(AGUIEvent::type).contains(AGUIEventType.TOOL_CALL_END);
                assertThat(outcome.interrupts().getFirst().toolCallId()).isEqualTo("call-approval");
                assertThat(interruptId).startsWith("interrupt-").doesNotContain("approval");
                assertThat(resumed)
                        .filteredOn(AGUIEvents.ToolCallResult.class::isInstance)
                        .singleElement()
                        .extracting(event -> ((AGUIEvents.ToolCallResult) event).toolCallId())
                        .isEqualTo("call-approval");
                assertThat(resumed).extracting(AGUIEvent::type).endsWith(AGUIEventType.RUN_FINISHED);
                assertThat(invocations).hasValue(1);
                assertThat(dispatcher.continuationCount()).isZero();
                assertThatThrownBy(() -> collect(client.resumeStreaming(resume)).get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(com.microsoft.agents.protocols.agui.AGUIProtocolException.class);
            }
        }
    }

    private static CompletableFuture<List<AGUIEvent>> collect(
            java.util.concurrent.Flow.Publisher<AGUIEvent> publisher) {
        CompletableFuture<List<AGUIEvent>> result = new CompletableFuture<>();
        java.util.ArrayList<AGUIEvent> events = new java.util.ArrayList<>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
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
