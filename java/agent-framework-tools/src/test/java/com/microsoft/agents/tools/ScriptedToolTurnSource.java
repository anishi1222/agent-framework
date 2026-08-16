// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

final class ScriptedToolTurnSource implements ToolTurnSource {
    private final Queue<CompletionStage<ChatResponse>> responses = new ArrayDeque<>();

    private final Queue<List<ChatResponseUpdate>> streamingResponses = new ArrayDeque<>();

    private final List<ToolTurnRequest> requests = new CopyOnWriteArrayList<>();

    synchronized ScriptedToolTurnSource enqueue(ChatResponse response) {
        responses.add(CompletableFuture.completedFuture(response));
        return this;
    }

    synchronized ScriptedToolTurnSource enqueue(CompletionStage<ChatResponse> response) {
        responses.add(response);
        return this;
    }

    synchronized ScriptedToolTurnSource enqueueStreaming(List<ChatResponseUpdate> updates) {
        streamingResponses.add(List.copyOf(updates));
        return this;
    }

    List<ToolTurnRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized CompletionStage<ChatResponse> completeAsync(
            ToolTurnRequest request, RunCancellation cancellation) {
        requests.add(request);
        CompletionStage<ChatResponse> response = responses.poll();
        if (response == null) {
            return CompletableFuture.failedFuture(new AssertionError("No scripted finite response remains."));
        }
        return response;
    }

    @Override
    public synchronized Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ToolTurnRequest request, RunCancellation cancellation) {
        requests.add(request);
        List<ChatResponseUpdate> updates = streamingResponses.poll();
        if (updates == null) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onError(new AssertionError("No scripted streaming response remains."));
                    }

                    @Override
                    public void cancel() {}
                });
            };
        }
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final List<ChatResponseUpdate> remaining = new ArrayList<>(updates);

            private boolean cancelled;

            private boolean completed;

            @Override
            public void request(long count) {
                if (cancelled || completed) {
                    return;
                }
                if (count <= 0) {
                    completed = true;
                    subscriber.onError(new IllegalArgumentException("Demand must be positive."));
                    return;
                }
                long emitted = 0;
                while (emitted < count && !remaining.isEmpty() && !cancelled) {
                    subscriber.onNext(remaining.removeFirst());
                    emitted++;
                }
                if (remaining.isEmpty() && !cancelled) {
                    completed = true;
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
