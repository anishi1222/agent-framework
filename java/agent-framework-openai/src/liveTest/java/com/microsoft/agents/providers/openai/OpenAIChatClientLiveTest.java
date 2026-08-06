// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIChatClientLiveTest {
    @Test
    void liveCompletion_shouldReachConfiguredOpenAiModel() {
        // Arrange
        OpenAIChatClientOptions options = OpenAIChatClientOptions.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .model(System.getenv("OPENAI_MODEL"))
                .build();

        // Act
        try (OpenAIChatClient client =
                OpenAIChatClient.builder().options(options).build()) {
            var response = client.completeAsync(new ChatClientRequest(
                            List.of(Message.text(Role.USER, "Reply with the single word: ready")),
                            ChatOptions.builder().maxTokens(8).build()))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(response.responseId()).isNotBlank();
            assertThat(response.text()).isNotBlank();
        }
    }
}
