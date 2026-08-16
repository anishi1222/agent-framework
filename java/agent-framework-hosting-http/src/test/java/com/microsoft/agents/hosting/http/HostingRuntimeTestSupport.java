// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class HostingRuntimeTestSupport {
    private HostingRuntimeTestSupport() {}

    static ChatAgent chatAgent(String id, ScriptedChatClient client) {
        return new ChatAgent(
                client,
                new AgentMetadata(id, id, "Production-path hosting test agent"),
                ChatOptions.empty(),
                List.of());
    }

    static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .finishReason(FinishReason.STOP)
                .build();
    }

    static ChatResponseUpdate update(long sequence, String text, FinishReason finishReason) {
        ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                .sequence(sequence)
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(text)));
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        return builder.build();
    }

    static Flow.Publisher<ChatResponseUpdate> scriptedPublisher(
            List<ChatResponseUpdate> updates, long delayMillis, CompletableFuture<Boolean> cancelled) {
        List<ChatResponseUpdate> copy = List.copyOf(updates);
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicLong demand = new AtomicLong();

            private final AtomicBoolean terminated = new AtomicBoolean();

            private int index;

            @Override
            public synchronized void request(long count) {
                if (count <= 0 || terminated.get()) {
                    return;
                }
                demand.updateAndGet(current -> addCap(current, count));
                ArrayList<ChatResponseUpdate> ready = new ArrayList<>();
                while (demand.get() > 0 && index < copy.size()) {
                    demand.decrementAndGet();
                    ready.add(copy.get(index++));
                }
                for (ChatResponseUpdate item : ready) {
                    if (delayMillis > 0 && item.sequence() != null && item.sequence() > 0) {
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    subscriber.onNext(item);
                }
                if (index == copy.size() && terminated.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                if (terminated.compareAndSet(false, true) && cancelled != null) {
                    cancelled.complete(true);
                }
            }
        });
    }

    static Flow.Publisher<ChatResponseUpdate> pendingPublisher(CompletableFuture<Boolean> cancelled) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean done = new AtomicBoolean();

            @Override
            public void request(long count) {
                // The transport remains pending until cancellation.
            }

            @Override
            public void cancel() {
                if (done.compareAndSet(false, true)) {
                    cancelled.complete(true);
                }
            }
        });
    }

    static Workflow<String, String> workflow(String id) {
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create(id, String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "process", FunctionExecutor.sync(String.class, String.class, (value, context) -> value + "-workflow"));
        return builder.entry(node).output(node).build();
    }

    private static long addCap(long left, long right) {
        long sum = left + right;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    static final class ScriptedChatClient implements ChatClient {
        @FunctionalInterface
        interface FiniteHandler {
            CompletionStage<ChatResponse> complete(ChatClientRequest request, RunCancellation cancellation);
        }

        @FunctionalInterface
        interface StreamingHandler {
            Flow.Publisher<ChatResponseUpdate> complete(ChatClientRequest request, RunCancellation cancellation);
        }

        private final ArrayDeque<FiniteHandler> finite = new ArrayDeque<>();

        private final ArrayDeque<StreamingHandler> streaming = new ArrayDeque<>();

        synchronized ScriptedChatClient enqueue(ChatResponse response) {
            finite.addLast((request, cancellation) -> CompletableFuture.completedFuture(response));
            return this;
        }

        synchronized ScriptedChatClient enqueueFinite(FiniteHandler handler) {
            finite.addLast(handler);
            return this;
        }

        synchronized ScriptedChatClient enqueueStreaming(StreamingHandler handler) {
            streaming.addLast(handler);
            return this;
        }

        @Override
        public synchronized CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest request, RunCancellation cancellation) {
            FiniteHandler handler = finite.pollFirst();
            return handler == null
                    ? CompletableFuture.failedFuture(new AssertionError("No finite response configured."))
                    : handler.complete(request, cancellation);
        }

        @Override
        public synchronized Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            StreamingHandler handler = streaming.pollFirst();
            if (handler == null) {
                return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onError(new AssertionError("No streaming response configured."));
                    }

                    @Override
                    public void cancel() {}
                });
            }
            return handler.complete(request, cancellation);
        }
    }
}
