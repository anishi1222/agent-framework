// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.SynchronousExecutionException;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ChatClientTest {
    @Test
    void defaultFiniteFamilies_shouldUseOneStartCompletionCorePerInvocation() {
        // Arrange
        ChatResponse first = response("async");
        ChatResponse second = response("sync");
        FakeChatClient client = new FakeChatClient().enqueue(first).enqueue(second);
        ChatClientRequest request =
                new ChatClientRequest(List.of(Message.text(Role.USER, "hello")), ChatOptions.empty());

        // Act
        ChatResponse async = client.completeAsync(request).toCompletableFuture().join();
        ChatResponse sync = client.complete(request);

        // Assert
        assertThat(async).isSameAs(first);
        assertThat(sync).isSameAs(second);
        assertThat(client.requests()).containsExactly(request, request);
        assertThat(client.cancellations()).hasSize(2);
    }

    @Test
    void startCompletion_shouldUseCallerCancellationAndRejectLateSuccess() {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> pending);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        ChatClientRequest request =
                new ChatClientRequest(List.of(Message.text(Role.USER, "wait")), ChatOptions.empty());

        // Act
        RunHandle<ChatResponse> handle = client.startCompletion(request, cancellation);
        boolean first = handle.cancel();
        boolean second = handle.cancel();
        pending.complete(response("late"));

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
    }

    @Test
    void complete_shouldWrapTypedFailureAndRequestShouldDefensivelyCopyMessages() {
        // Arrange
        AgentExecutionException failure = new AgentExecutionException("provider failure");
        FakeChatClient client = new FakeChatClient().enqueueFailure(failure);
        ArrayList<Message> callerMessages = new ArrayList<>(List.of(Message.text(Role.USER, "hello")));
        ChatClientRequest request = new ChatClientRequest(callerMessages, ChatOptions.empty());
        callerMessages.add(Message.text(Role.USER, "later"));

        // Act and assert
        assertThat(request.messages()).containsExactly(Message.text(Role.USER, "hello"));
        assertThatThrownBy(() -> client.complete(request))
                .isInstanceOf(SynchronousExecutionException.class)
                .hasCause(failure);
        assertThatThrownBy(() -> new ChatClientRequest(null, ChatOptions.empty()))
                .isInstanceOf(ValidationException.class);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(
                List.of(Message.text(Role.ASSISTANT, text)),
                null,
                null,
                null,
                null,
                FinishReason.STOP,
                null,
                null,
                Map.of(),
                List.of());
    }
}
