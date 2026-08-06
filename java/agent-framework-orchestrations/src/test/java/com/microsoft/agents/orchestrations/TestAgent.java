// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TestAgent implements Agent<Void> {
    @FunctionalInterface
    interface Handler {
        AgentResponse<Void> respond(List<Message> messages, RunOptions options, int invocation) throws Exception;
    }

    private final AgentMetadata metadata;

    private final long delayMillis;

    private final Handler handler;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicInteger invocationCount = new AtomicInteger();

    private final CopyOnWriteArrayList<List<Message>> inputs = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<RunOptions> options = new CopyOnWriteArrayList<>();

    private final AtomicBoolean cancellationObserved = new AtomicBoolean();

    private final CompletableFuture<Void> firstInvocation = new CompletableFuture<>();

    TestAgent(String id, long delayMillis, Handler handler) {
        metadata = new AgentMetadata(id, id, "Deterministic test agent " + id);
        this.delayMillis = delayMillis;
        this.handler = java.util.Objects.requireNonNull(handler, "handler");
    }

    static TestAgent responding(String id, String text) {
        return new TestAgent(id, 0, (messages, options, invocation) -> response(id, text));
    }

    static AgentResponse<Void> response(String id, String text) {
        return AgentResponse.<Void>builder()
                .agentId(id)
                .messages(List.of(Message.builder(Role.ASSISTANT)
                        .contents(List.of(new com.microsoft.agents.core.TextContent(text)))
                        .authorName(id)
                        .messageId(id + "-message-" + text)
                        .build()))
                .build();
    }

    @Override
    public AgentMetadata metadata() {
        return metadata;
    }

    @Override
    public RunHandle<AgentResponse<Void>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        List<Message> copied = List.copyOf(messages);
        inputs.add(copied);
        this.options.add(options);
        int invocation = invocationCount.getAndIncrement();
        firstInvocation.complete(null);
        RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
        executor.execute(() -> {
            try {
                long remaining = delayMillis;
                while (remaining > 0) {
                    if (source.cancellation().isCancellationRequested()) {
                        cancellationObserved.set(true);
                        return;
                    }
                    long wait = Math.min(remaining, 10);
                    Thread.sleep(wait);
                    remaining -= wait;
                }
                if (source.cancellation().isCancellationRequested()) {
                    cancellationObserved.set(true);
                    return;
                }
                source.tryComplete(handler.respond(copied, options, invocation));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                source.tryFail(new RunCancelledException("Test agent was interrupted.", failure));
            } catch (Throwable failure) {
                source.tryFail(failure);
            }
        });
        return source.handle();
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean terminated = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (count <= 0 && terminated.compareAndSet(false, true)) {
                    subscriber.onError(new IllegalArgumentException("demand must be positive"));
                } else if (terminated.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancellation.cancel();
                terminated.set(true);
            }
        });
    }

    int invocationCount() {
        return invocationCount.get();
    }

    List<List<Message>> inputs() {
        return List.copyOf(inputs);
    }

    List<RunOptions> runOptions() {
        return List.copyOf(options);
    }

    AtomicBoolean cancellationObserved() {
        return cancellationObserved;
    }

    CompletableFuture<Void> firstInvocation() {
        return firstInvocation;
    }

    @Override
    public void close() {
        executor.close();
    }
}
