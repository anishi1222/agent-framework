// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.s02;

import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs an exactly-once local function tool through the agent tool loop. */
public final class ToolCallingSample {
    private ToolCallingSample() {}

    /**
     * Runs the sample.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool weather = FunctionTool.create(
                new ToolMetadata(
                        "weather",
                        "Returns deterministic sample weather.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of("type", StateValue.string("object"))),
                        StateValue.object(Map.of())),
                (context, arguments) -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture(StateValue.string("sunny"));
                });

        try (ChatAgent agent = new ChatAgent(new ToolCallingChatClient(), List.of(weather))) {
            String text = agent.run("weather in Seattle").text();
            require("weather:sunny".equals(text), "Unexpected response: " + text);
            require(invocations.get() == 1, "Tool invocation count was " + invocations.get());
            System.out.println(text);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class ToolCallingChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ChatResponse.builder()
                        .messages(List.of(new Message(
                                Role.ASSISTANT,
                                List.of(new FunctionCallContent(
                                        "sample-call", "weather", StateValue.object(Map.of()))))))
                        .responseId("tool-request")
                        .createdAt(Instant.EPOCH)
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
            }
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "weather:sunny")))
                    .responseId("tool-result")
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }
}
