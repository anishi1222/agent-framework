// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.samples.common;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.TextContent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Provides a deterministic offline chat client for executable samples. */
public final class PrefixChatClient implements ChatClient {
    private final String prefix;

    /**
     * Creates a client that prefixes the latest input message.
     *
     * @param prefix response prefix
     */
    public PrefixChatClient(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
        return CompletableFuture.completedFuture(response(request));
    }

    @Override
    public Flow.Publisher<ChatResponseUpdate> completeStreaming(
            ChatClientRequest request, RunCancellation cancellation) {
        ChatResponse response = response(request);
        ChatResponseUpdate update = ChatResponseUpdate.builder()
                .sequence(0)
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(response.text())))
                .responseId(response.responseId())
                .createdAt(response.createdAt())
                .finishReason(response.finishReason())
                .build();
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean signalled;

            @Override
            public void request(long count) {
                if (signalled) {
                    return;
                }
                signalled = true;
                subscriber.onNext(update);
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                signalled = true;
            }
        });
    }

    private ChatResponse response(ChatClientRequest request) {
        String input = request.messages().getLast().text();
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, prefix + input)))
                .responseId("offline-response")
                .createdAt(Instant.EPOCH)
                .finishReason(FinishReason.STOP)
                .build();
    }
}
