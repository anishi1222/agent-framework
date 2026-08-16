// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class AzureOpenAIChatClientLiveTest {
    @Test
    void liveCompletion_shouldReturnText() {
        AzureOpenAIChatClientOptions options = AzureOpenAIChatClientOptions.builder()
                .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
                .deployment(System.getenv("AZURE_OPENAI_DEPLOYMENT"))
                .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
                .build();

        try (AzureOpenAIChatClient client =
                AzureOpenAIChatClient.builder().options(options).build()) {
            var response = client.completeAsync(
                            List.of(Message.text(Role.USER, "Reply with the word ready.")), ChatOptions.empty())
                    .toCompletableFuture()
                    .join();
            assertThat(response.text()).isNotBlank();
        }
    }
}
