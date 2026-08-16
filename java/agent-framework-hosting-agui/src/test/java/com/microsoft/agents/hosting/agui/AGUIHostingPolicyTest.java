// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.http.HostingHttpRequest;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.protocols.agui.AGUIInterrupt;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUILimits;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.AGUIResumeEntry;
import com.microsoft.agents.protocols.agui.AGUIResumeStatus;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AGUIHostingPolicyTest {
    @Test
    void handler_shouldRejectConcurrentAndExpiredPrincipalScopedThreadBoundaries() throws Exception {
        // Arrange
        HostingLimits hostingLimits = HostingLimits.defaults();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient();
        try (ChatAgent agent = AGUIHostingTestSupport.chatAgent("policy", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore store = new InMemoryAGUIThreadStore(8, Duration.ofMinutes(5))) {
            routes.registerAgent("/ag-ui/policy", agent);
            AGUIJsonCodec codec = new AGUIJsonCodec(aguiLimits);
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher,
                    routes,
                    store,
                    HostingHttpServerOptions.builder().limits(hostingLimits).build(),
                    AGUIHostingOptions.defaults(),
                    codec);
            AGUIThreadKey activeKey =
                    new AGUIThreadKey("local", "local", HostingRouteKind.AGENT, "policy", "active-thread");
            store.compareAndSetAsync(
                            activeKey,
                            new AGUIThreadState(
                                    List.of(), StateValue.object(Map.of()), "already-running", null, Instant.now()),
                            0)
                    .toCompletableFuture()
                    .join();
            AGUIThreadKey expiredKey =
                    new AGUIThreadKey("local", "local", HostingRouteKind.AGENT, "policy", "expired-thread");
            AGUIInterrupt expiredInterrupt = new AGUIInterrupt(
                    "interrupt-expired",
                    "input_required",
                    "expired",
                    null,
                    null,
                    Instant.now().minusSeconds(1),
                    Map.of());
            store.compareAndSetAsync(
                            expiredKey,
                            new AGUIThreadState(
                                    List.of(),
                                    StateValue.object(Map.of()),
                                    null,
                                    new AGUIPendingContinuation(
                                            "old-run",
                                            "host-run",
                                            HostingRouteKind.AGENT,
                                            "opaque-token",
                                            HostingContinuationType.INPUT,
                                            List.of(expiredInterrupt),
                                            Map.of(),
                                            expiredInterrupt.expiresAt()),
                                    Instant.now()),
                            0)
                    .toCompletableFuture()
                    .join();

            // Act
            AGUIHttpResponse concurrent = handler.handleAsync(
                            request(codec, input("active-thread", "new-run", List.of())))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            AGUIHttpResponse expired = handler.handleAsync(request(
                            codec,
                            input(
                                    "expired-thread",
                                    "resume-run",
                                    List.of(new AGUIResumeEntry(
                                            "interrupt-expired",
                                            AGUIResumeStatus.RESOLVED,
                                            StateValue.string("value"))))))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(concurrent.status()).isEqualTo(409);
            assertThat(expired.status()).isEqualTo(409);
            assertThat(dispatcher.activeRunCount()).isZero();
            handler.close();
        }
    }

    private static HostingHttpRequest request(AGUIJsonCodec codec, RunAgentInput input) {
        return new HostingHttpRequest(
                "POST",
                URI.create("/ag-ui/policy"),
                new InetSocketAddress("127.0.0.1", 32000),
                Map.of(
                        "host",
                        List.of("localhost:8080"),
                        "content-type",
                        List.of("application/json"),
                        "accept",
                        List.of("text/event-stream")),
                codec.encodeRunAgentInput(input),
                new DefaultRunCancellation());
    }

    private static RunAgentInput input(String thread, String run, List<AGUIResumeEntry> resume) {
        return new RunAgentInput(
                thread,
                run,
                null,
                StateValue.object(Map.of()),
                List.of(new AGUIMessages.User("user", new AGUIMessages.TextUserContent("hello"), null, null)),
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
}
