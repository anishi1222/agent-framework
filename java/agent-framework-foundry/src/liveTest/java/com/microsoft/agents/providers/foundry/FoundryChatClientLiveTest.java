// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoundryChatClientLiveTest {
    @Test
    void liveCompletion_shouldReturnText() {
        FoundryChatClientOptions.Builder options = FoundryChatClientOptions.builder()
                .projectEndpoint(System.getenv("FOUNDRY_PROJECT_ENDPOINT"))
                .defaultAzureCredential();
        String model = System.getenv("FOUNDRY_MODEL");
        if (model == null || model.isBlank()) {
            options.agentName(System.getenv("FOUNDRY_AGENT_NAME"));
            String version = System.getenv("FOUNDRY_AGENT_VERSION");
            if (version != null && !version.isBlank()) {
                options.agentVersion(version);
            }
        } else {
            options.model(model);
        }

        try (FoundryChatClient client =
                FoundryChatClient.builder().options(options.build()).build()) {
            var response = client.completeAsync(
                            List.of(Message.text(Role.USER, "Reply with the word ready.")), ChatOptions.empty())
                    .toCompletableFuture()
                    .join();
            assertThat(response.text()).isNotBlank();
        }
    }
}
