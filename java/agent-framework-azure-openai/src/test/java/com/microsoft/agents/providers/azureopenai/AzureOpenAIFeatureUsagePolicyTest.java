// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpRequest;
import com.microsoft.agents.core.FeatureUsageIndex;
import com.microsoft.agents.core.FeatureUsageRegistry;
import com.microsoft.agents.core.UserAgentUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AzureOpenAIFeatureUsagePolicyTest {
    @Test
    void process_shouldRestoreOriginalUserAgentWhenLaterAttemptUsesDeniedOrigin() {
        // Arrange
        FeatureUsageRegistry.global().markUsed(new FeatureUsageIndex(56, "openai"));
        HttpRequest request = new HttpRequest(HttpMethod.POST, "https://resource.openai.azure.com/openai/v1/responses");
        request.setHeader(HttpHeaderName.fromString(UserAgentUtil.USER_AGENT_HEADER), "azsdk-java-test/1.0");
        HttpPipelineCallContext context = mock(HttpPipelineCallContext.class);
        HttpPipelineNextPolicy next = mock(HttpPipelineNextPolicy.class);
        Map<String, Object> contextData = new HashMap<>();
        when(context.getHttpRequest()).thenReturn(request);
        when(context.getData(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(contextData.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
                    contextData.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(context)
                .setData(anyString(), any());
        when(next.process()).thenReturn(Mono.empty());
        AzureOpenAIFeatureUsagePolicy policy = new AzureOpenAIFeatureUsagePolicy();

        // Act
        policy.process(context, next).block();
        String approvedUserAgent =
                request.getHeaders().getValue(HttpHeaderName.fromString(UserAgentUtil.USER_AGENT_HEADER));
        request.setUrl("https://gateway.example.com/openai/v1/responses");
        policy.process(context, next).block();
        String deniedUserAgent =
                request.getHeaders().getValue(HttpHeaderName.fromString(UserAgentUtil.USER_AGENT_HEADER));

        // Assert
        assertThat(policy.getPipelinePosition()).isEqualTo(HttpPipelinePosition.PER_RETRY);
        assertThat(approvedUserAgent).contains("agent-framework-java/").contains("(feat=v1.");
        assertThat(deniedUserAgent).isEqualTo("azsdk-java-test/1.0");
    }
}
