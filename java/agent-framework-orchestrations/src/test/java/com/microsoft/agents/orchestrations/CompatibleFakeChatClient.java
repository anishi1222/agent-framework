// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

final class CompatibleFakeChatClient implements ChatClient {
    private final String transportKind;

    private final CopyOnWriteArrayList<ChatClientRequest> requests = new CopyOnWriteArrayList<>();

    CompatibleFakeChatClient(String transportKind) {
        this.transportKind = transportKind;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        requests.add(request);
        String last =
                request.messages().isEmpty() ? "" : request.messages().getLast().text();
        return CompletableFuture.completedFuture(ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, transportKind + ":" + last)))
                .build());
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean terminated = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (terminated.compareAndSet(false, true)) {
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

    List<ChatClientRequest> requests() {
        return List.copyOf(requests);
    }
}
