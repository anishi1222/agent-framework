// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRequestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingWebSocketProtocolTest {
    @Test
    void protocol_shouldBoundPendingOutboundMessagesBehindSlowPeer() {
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        HostingRuntimeTestSupport.ScriptedChatClient transport = new HostingRuntimeTestSupport.ScriptedChatClient()
                .enqueueStreaming((request, cancellation) ->
                        subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                            private final AtomicInteger index = new AtomicInteger();

                            @Override
                            public void request(long count) {
                                long remaining = count;
                                while (remaining-- > 0 && index.get() < 3) {
                                    int next = index.getAndIncrement();
                                    subscriber.onNext(HostingRuntimeTestSupport.update(
                                            next, "update-" + next, next == 2 ? FinishReason.STOP : null));
                                }
                            }

                            @Override
                            public void cancel() {
                                cancelled.complete(true);
                            }
                        }));
        HostingLimits limits =
                HostingLimits.builder().maxWebSocketBufferedMessages(1).build();
        HostingRegistry registry = new HostingRegistry();
        SlowPeer peer = new SlowPeer();
        try (ChatAgent agent = HostingRuntimeTestSupport.chatAgent("slow-peer-agent", transport);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits);
                HostingWebSocketProtocol protocol = new HostingWebSocketProtocol(
                        dispatcher,
                        new HostingJsonCodec(limits),
                        HostingHttpServerOptions.builder().limits(limits).build())) {
            registry.registerAgent(agent);
            HostingWebSocketConnection connection = protocol.open(context(), peer);
            connection.receiveText(startFrame());
            assertThat(peer.messages).hasSize(1);

            connection.receiveText(demandFrame());

            assertThat(peer.closeCode.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(1009);
            assertThat(cancelled.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
            assertThat(connection.isOpen()).isFalse();
        }
    }

    private static HostingRequestContext context() {
        return new HostingRequestContext(
                "request",
                "correlation",
                new HostingPrincipal("owner", "tenant"),
                Map.of(),
                Map.of(),
                new DefaultRunCancellation());
    }

    private static String startFrame() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"start",
                  "operationId":"slow-operation",
                  "kind":"agent",
                  "routeId":"slow-peer-agent",
                  "request":{
                    "version":"java-hosting-2026-08-01",
                    "messages":[
                      {"role":"user","contents":[{"kind":"text","text":"hello"}]}
                    ]
                  }
                }
                """;
    }

    private static String demandFrame() {
        return """
                {
                  "version":"java-hosting-2026-08-01",
                  "type":"demand",
                  "operationId":"slow-operation",
                  "count":3
                }
                """;
    }

    private static final class SlowPeer implements HostingWebSocketPeer {
        private final List<String> messages = new ArrayList<>();

        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> sendTextAsync(String text) {
            messages.add(text);
            return new CompletableFuture<>();
        }

        @Override
        public CompletionStage<Void> pingAsync(byte[] payload) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> closeAsync(int code, String reason) {
            closeCode.complete(code);
            return CompletableFuture.completedFuture(null);
        }
    }
}
