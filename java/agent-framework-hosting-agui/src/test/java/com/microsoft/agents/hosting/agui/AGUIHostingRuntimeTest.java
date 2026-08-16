// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.protocols.agui.AGUIClient;
import com.microsoft.agents.protocols.agui.AGUIClientCapabilities;
import com.microsoft.agents.protocols.agui.AGUIClientOptions;
import com.microsoft.agents.protocols.agui.AGUIContext;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEventType;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUILimits;
import com.microsoft.agents.protocols.agui.AGUIMessage;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.RunAgentInput;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AGUIHostingRuntimeTest {
    @Test
    void clientAndHost_shouldStreamGraduallyWithoutDuplicateMessages() throws Exception {
        // Arrange
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> AGUIHostingTestSupport.publisher(
                        List.of(
                                AGUIHostingTestSupport.text(0, "assistant-1", "hello ", null),
                                AGUIHostingTestSupport.text(1, "assistant-1", "world", FinishReason.STOP)),
                        80,
                        null));
        HostingLimits hostingLimits =
                HostingLimits.builder().idleTimeout(Duration.ofSeconds(3)).build();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        try (ChatAgent agent = AGUIHostingTestSupport.chatAgent("chat", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(32, Duration.ofMinutes(5))) {
            routes.registerAgent(AGUIHostingRegistry.DEFAULT_PATH, agent);
            HostingHttpServerOptions httpOptions =
                    HostingHttpServerOptions.builder().limits(hostingLimits).build();
            AGUIJsonCodec codec = new AGUIJsonCodec(aguiLimits);
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher, routes, threads, httpOptions, AGUIHostingOptions.defaults(), codec);
            try (AGUIHttpServer server = AGUIHttpServer.start(handler);
                    AGUIClient client = new AGUIClient(
                            AGUIClientOptions.builder(resolve(server.endpoint(), AGUIHostingRegistry.DEFAULT_PATH))
                                    .allowInsecureLoopback()
                                    .limits(aguiLimits)
                                    .build())) {
                ArrayList<AGUIEvent> events = new ArrayList<>();
                ArrayList<Long> contentTimes = new ArrayList<>();
                CompletableFuture<Void> completed = new CompletableFuture<>();

                // Act
                AGUIClientCapabilities capabilities =
                        client.capabilitiesAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
                client.runStreaming(input("thread-1", "run-1")).subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(AGUIEvent item) {
                        events.add(item);
                        if (item.type() == AGUIEventType.TEXT_MESSAGE_CONTENT) {
                            contentTimes.add(System.nanoTime());
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        completed.completeExceptionally(throwable);
                    }

                    @Override
                    public void onComplete() {
                        completed.complete(null);
                    }
                });
                completed.get(5, TimeUnit.SECONDS);

                // Assert
                assertThat(capabilities.schemaVersion()).isEqualTo("0.0.57");
                assertThat(capabilities.sse()).isTrue();
                assertThat(events).extracting(AGUIEvent::type).startsWith(AGUIEventType.RUN_STARTED);
                assertThat(events).extracting(AGUIEvent::type).endsWith(AGUIEventType.RUN_FINISHED);
                assertThat(events.stream().filter(event -> event.type() == AGUIEventType.TEXT_MESSAGE_START))
                        .hasSize(1);
                assertThat(events.stream()
                                .filter(event -> event.type() == AGUIEventType.TEXT_MESSAGE_CONTENT)
                                .map(com.microsoft.agents.protocols.agui.AGUIEvents.TextMessageContent.class::cast)
                                .map(com.microsoft.agents.protocols.agui.AGUIEvents.TextMessageContent::delta))
                        .containsExactly("hello ", "world");
                assertThat(TimeUnit.NANOSECONDS.toMillis(contentTimes.get(1) - contentTimes.get(0)))
                        .isGreaterThanOrEqualTo(40);
                assertThat(dispatcher.activeRunCount()).isZero();
                assertThat(threads.size()).isEqualTo(1);
            }
        }
    }

    @Test
    void disconnect_shouldCancelProductionAgentRun() throws Exception {
        // Arrange
        CompletableFuture<Boolean> providerCancelled = new CompletableFuture<>();
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) -> AGUIHostingTestSupport.pending(providerCancelled));
        HostingLimits hostingLimits = HostingLimits.builder()
                .idleTimeout(Duration.ofSeconds(3))
                .runTimeout(Duration.ofSeconds(10))
                .build();
        AGUILimits aguiLimits = limits(hostingLimits);
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        try (ChatAgent agent = AGUIHostingTestSupport.chatAgent("pending", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(8, Duration.ofMinutes(5))) {
            routes.registerAgent("/ag-ui/pending", agent);
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher,
                    routes,
                    threads,
                    HostingHttpServerOptions.builder().limits(hostingLimits).build(),
                    AGUIHostingOptions.defaults(),
                    new AGUIJsonCodec(aguiLimits));
            try (AGUIHttpServer server = AGUIHttpServer.start(handler);
                    AGUIClient client =
                            new AGUIClient(AGUIClientOptions.builder(resolve(server.endpoint(), "/ag-ui/pending"))
                                    .allowInsecureLoopback()
                                    .limits(aguiLimits)
                                    .build())) {
                AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
                CompletableFuture<Void> started = new CompletableFuture<>();
                client.runStreaming(input("thread", "run")).subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription value) {
                        subscription.set(value);
                        value.request(2);
                    }

                    @Override
                    public void onNext(AGUIEvent item) {
                        started.complete(null);
                    }

                    @Override
                    public void onError(Throwable throwable) {}

                    @Override
                    public void onComplete() {}
                });
                started.get(5, TimeUnit.SECONDS);

                // Act
                subscription.get().cancel();

                // Assert
                assertThat(providerCancelled.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void host_shouldRejectOriginMediaReplayAndAcceptErrorsBeforeDispatch() throws Exception {
        // Arrange
        AGUIHostingTestSupport.ScriptedChatClient transport = new AGUIHostingTestSupport.ScriptedChatClient();
        HostingLimits hostingLimits = HostingLimits.defaults();
        HostingRegistry generic = new HostingRegistry();
        AGUIHostingRegistry routes = new AGUIHostingRegistry(generic);
        try (ChatAgent agent = AGUIHostingTestSupport.chatAgent("secure", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(generic, hostingLimits);
                InMemoryAGUIThreadStore threads = new InMemoryAGUIThreadStore(8, Duration.ofMinutes(5))) {
            routes.registerAgent("/ag-ui/secure", agent);
            AGUIJsonCodec codec = new AGUIJsonCodec(limits(hostingLimits));
            AGUIHostingHttpHandler handler = new AGUIHostingHttpHandler(
                    dispatcher,
                    routes,
                    threads,
                    HostingHttpServerOptions.builder()
                            .limits(hostingLimits)
                            .allowedOrigins(java.util.Set.of("http://allowed.example"))
                            .build(),
                    AGUIHostingOptions.defaults(),
                    codec);
            try (AGUIHttpServer server = AGUIHttpServer.start(handler);
                    HttpClient client = HttpClient.newHttpClient()) {
                URI endpoint = resolve(server.endpoint(), "/ag-ui/secure");
                String body = new String(
                        codec.encodeRunAgentInput(input("thread", "run")), java.nio.charset.StandardCharsets.UTF_8);

                // Act
                HttpResponse<String> origin = client.send(
                        request(endpoint, body, "application/json", "text/event-stream")
                                .header("Origin", "http://evil.example")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> media = client.send(
                        request(endpoint, body, "text/plain", "text/event-stream")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> replay = client.send(
                        request(endpoint, body, "application/json", "text/event-stream")
                                .header("Last-Event-ID", "1")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> accept = client.send(
                        request(endpoint, body, "application/json", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                // Assert
                assertThat(origin.statusCode()).isEqualTo(403);
                assertThat(media.statusCode()).isEqualTo(415);
                assertThat(replay.statusCode()).isEqualTo(422);
                assertThat(accept.statusCode()).isEqualTo(406);
                assertThat(dispatcher.activeRunCount()).isZero();
            }
        }
    }

    private static HttpRequest.Builder request(URI endpoint, String body, String contentType, String accept) {
        return HttpRequest.newBuilder(endpoint)
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static RunAgentInput input(String threadId, String runId) {
        List<AGUIMessage> messages =
                List.of(new AGUIMessages.User("user-1", new AGUIMessages.TextUserContent("hello"), null, null));
        return new RunAgentInput(
                threadId,
                runId,
                StateValue.object(Map.of()),
                messages,
                List.of(),
                List.of(new AGUIContext("test", "runtime")),
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
