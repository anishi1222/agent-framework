// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpPipelinePosition;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.microsoft.agents.core.FeatureUsageRegistry;
import com.microsoft.agents.core.UserAgentUtil;
import java.net.URI;
import java.util.List;
import reactor.core.publisher.Mono;

final class AzureOpenAIFeatureUsagePolicy implements HttpPipelinePolicy {
    private static final String ORIGINAL_USER_AGENT_KEY =
            AzureOpenAIFeatureUsagePolicy.class.getName() + ".originalUserAgent";

    private static final HttpHeaderName USER_AGENT = HttpHeaderName.fromString(UserAgentUtil.USER_AGENT_HEADER);

    private static final List<String> APPROVED_ORIGIN_SUFFIXES =
            List.of("cognitiveservices.azure.com", "openai.azure.com", "services.ai.azure.com");

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        var request = context.getHttpRequest();
        String existing = request.getHeaders().getValue(USER_AGENT);
        OriginalUserAgent originalUserAgent = context.getData(ORIGINAL_USER_AGENT_KEY)
                .filter(OriginalUserAgent.class::isInstance)
                .map(OriginalUserAgent.class::cast)
                .orElseGet(() -> {
                    OriginalUserAgent original = new OriginalUserAgent(existing);
                    context.setData(ORIGINAL_USER_AGENT_KEY, original);
                    return original;
                });
        URI requestUri = URI.create(request.getUrl().toExternalForm());
        String stamped;
        if (UserAgentUtil.isApprovedHttpsOrigin(requestUri, APPROVED_ORIGIN_SUFFIXES)) {
            String frameworkUserAgent = UserAgentUtil.prependFrameworkUserAgent(originalUserAgent.value());
            stamped = UserAgentUtil.applyFeatureToken(frameworkUserAgent, FeatureUsageRegistry.global());
        } else {
            stamped = UserAgentUtil.removeFeatureToken(originalUserAgent.value());
        }
        if (stamped.isEmpty()) {
            request.getHeaders().remove(USER_AGENT);
        } else {
            request.getHeaders().set(USER_AGENT, stamped);
        }
        return next.process();
    }

    @Override
    public HttpPipelinePosition getPipelinePosition() {
        return HttpPipelinePosition.PER_RETRY;
    }

    private record OriginalUserAgent(String value) {}
}
