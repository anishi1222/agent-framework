// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui.spring;

import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.agui.AGUIHostingHttpHandler;
import com.microsoft.agents.hosting.agui.AGUIHostingOptions;
import com.microsoft.agents.hosting.agui.AGUIHostingRegistry;
import com.microsoft.agents.hosting.agui.AGUIPrincipalResolver;
import com.microsoft.agents.hosting.agui.AGUIThreadStore;
import com.microsoft.agents.hosting.agui.InMemoryAGUIThreadStore;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.hosting.spring.AgentFrameworkHostingAutoConfiguration;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUILimits;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Auto-configures opt-in AG-UI routes over the existing generic Spring WebFlux hosting beans.
 *
 * <p>No global Spring Security or CORS configuration is installed. Applications register exact
 * targets on the {@link AGUIHostingRegistry}.
 */
@AutoConfiguration
@AutoConfigureAfter(AgentFrameworkHostingAutoConfiguration.class)
@ConditionalOnClass({RouterFunction.class, AGUIHostingHttpHandler.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = AgentFrameworkAGUIHostingProperties.PREFIX, name = "enabled", havingValue = "true")
@ConditionalOnBean({
    HostingDispatcher.class,
    HostingRegistry.class,
    HostingHttpHandler.class,
    HostingHttpServerOptions.class
})
@EnableConfigurationProperties(AgentFrameworkAGUIHostingProperties.class)
public final class AgentFrameworkAGUIHostingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AGUILimits agentFrameworkAGUILimits(HostingLimits limits) {
        return new AGUILimits(
                limits.maxRequestBytes(),
                limits.maxResponseBytes(),
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                limits.maxNumericTokenLength(),
                limits.maxCollectionEntries(),
                Math.min(limits.maxCollectionEntries(), 1_000),
                limits.maxWebSocketFrameBytes(),
                limits.maxEventsPerRun(),
                limits.maxSseBufferedEvents());
    }

    @Bean
    @ConditionalOnMissingBean
    AGUIJsonCodec agentFrameworkAGUIJsonCodec(AGUILimits limits) {
        return new AGUIJsonCodec(limits);
    }

    @Bean
    @ConditionalOnMissingBean
    AGUIHostingRegistry agentFrameworkAGUIHostingRegistry(HostingRegistry registry) {
        return new AGUIHostingRegistry(registry);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AGUIThreadStore agentFrameworkAGUIThreadStore(AgentFrameworkAGUIHostingProperties properties) {
        return new InMemoryAGUIThreadStore(properties.getMaxThreads(), properties.getThreadTimeToLive());
    }

    @Bean
    @ConditionalOnMissingBean
    AGUIHostingOptions agentFrameworkAGUIHostingOptions(AgentFrameworkAGUIHostingProperties properties) {
        AGUIHostingOptions.Builder builder = AGUIHostingOptions.builder();
        if (properties.isIncludeRunInput()) {
            builder.includeRunInput();
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    AGUIPrincipalResolver agentFrameworkAGUIPrincipalResolver(HostingAuthenticator authenticator) {
        return (authenticatedName, request) -> {
            if (authenticatedName == null || authenticatedName.isBlank()) {
                return authenticator.authenticateAsync(request);
            }
            return CompletableFuture.completedFuture(
                    HostingAuthentication.authenticated(new HostingPrincipal(authenticatedName, authenticatedName)));
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AGUIHostingHttpHandler agentFrameworkAGUIHostingHttpHandler(
            HostingDispatcher dispatcher,
            AGUIHostingRegistry registry,
            AGUIThreadStore threadStore,
            HostingHttpServerOptions transportOptions,
            AGUIHostingOptions options,
            AGUIJsonCodec codec,
            HostingHttpHandler transportHandler) {
        return new AGUIHostingHttpHandler(
                dispatcher, registry, threadStore, transportOptions, options, codec, transportHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    SpringAGUIHostingHandler agentFrameworkSpringAGUIHostingHandler(
            AGUIHostingHttpHandler handler, AGUIPrincipalResolver principalResolver) {
        return new SpringAGUIHostingHandler(handler, principalResolver);
    }

    @Bean("agentFrameworkAGUIHostingRoutes")
    @ConditionalOnMissingBean(name = "agentFrameworkAGUIHostingRoutes")
    RouterFunction<ServerResponse> agentFrameworkAGUIHostingRoutes(
            SpringAGUIHostingHandler handler, AgentFrameworkAGUIHostingProperties properties) {
        String base = properties.getBasePath();
        return RouterFunctions.route(
                RequestPredicates.path(base).or(RequestPredicates.path(base + "/**")), handler::handle);
    }
}
