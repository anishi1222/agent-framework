// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.memory.MemoryScope;
import com.microsoft.agents.azure.AzureAuthenticationProviders;
import com.microsoft.agents.core.DefaultRunCancellation;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class AzureAISearchLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "AZURE_AI_SEARCH_LIVE_TEST", matches = "(?i)true")
    void liveSearch_shouldExecuteReadOnlyScopedQuery() {
        Map<String, String> environment = System.getenv();
        AzureAISearchAuthentication authentication = environment.containsKey("AZURE_AI_SEARCH_API_KEY")
                ? AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of(environment.get("AZURE_AI_SEARCH_API_KEY")))
                : AzureAISearchAuthentication.rbac(AzureAuthenticationProviders.defaultCredential());
        AzureAISearchFieldMapping mapping = AzureAISearchFieldMapping.builder()
                .keyField(environment.getOrDefault("AZURE_AI_SEARCH_KEY_FIELD", "id"))
                .contentField(environment.getOrDefault("AZURE_AI_SEARCH_CONTENT_FIELD", "content"))
                .titleField(environment.get("AZURE_AI_SEARCH_TITLE_FIELD"))
                .sourceUrlField(environment.get("AZURE_AI_SEARCH_SOURCE_URL_FIELD"))
                .tenantIdField(environment.getOrDefault("AZURE_AI_SEARCH_TENANT_FIELD", "tenantId"))
                .scopeIdField(environment.getOrDefault("AZURE_AI_SEARCH_SCOPE_FIELD", "scopeId"))
                .build();
        AzureAISearchOptions options = AzureAISearchOptions.forIndex(
                        AzureAISearchEndpoint.of(required(environment, "AZURE_AI_SEARCH_ENDPOINT")),
                        required(environment, "AZURE_AI_SEARCH_INDEX"),
                        authentication)
                .fieldMapping(mapping)
                .build();
        AzureAISearchContextProvider provider = new AzureAISearchContextProvider(
                options,
                new MemoryScope(
                        required(environment, "AZURE_AI_SEARCH_TENANT_ID"),
                        required(environment, "AZURE_AI_SEARCH_SCOPE_ID")));

        var results = provider.searchAsync(
                        new MemoryScope(
                                required(environment, "AZURE_AI_SEARCH_TENANT_ID"),
                                required(environment, "AZURE_AI_SEARCH_SCOPE_ID")),
                        required(environment, "AZURE_AI_SEARCH_QUERY"),
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        assertThat(results).isNotNull();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when live testing is enabled.");
        }
        return value;
    }
}
