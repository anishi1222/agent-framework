// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingAuthorizer;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.http.HostingHttpHandler;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 * Auto-configures the opt-in Agent Framework JSON and SSE routes for Spring WebFlux.
 *
 * <p>Every bean backs off for an application bean of the same contract. No Spring Security or CORS
 * filter is installed, and WebSocket hosting remains available only from
 * {@code agent-framework-hosting-http}.
 */
@AutoConfiguration
@ConditionalOnClass({RouterFunction.class, HostingHttpHandler.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix = AgentFrameworkHostingProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentFrameworkHostingProperties.class)
public final class AgentFrameworkHostingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    HostingLimits agentFrameworkHostingLimits() {
        return HostingLimits.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    HostingRegistry agentFrameworkHostingRegistry() {
        return new HostingRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    HostingAuthenticator agentFrameworkHostingAuthenticator() {
        return HostingAuthenticator.localOnly();
    }

    @Bean
    @ConditionalOnMissingBean
    HostingAuthorizer agentFrameworkHostingAuthorizer() {
        return HostingAuthorizer.allowAuthenticated();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    HostingDispatcher agentFrameworkHostingDispatcher(
            HostingRegistry registry, HostingLimits limits, HostingAuthorizer authorizer) {
        return new HostingDispatcher(registry, limits, authorizer);
    }

    @Bean
    @ConditionalOnMissingBean
    HostingHttpServerOptions agentFrameworkHostingHttpServerOptions(
            AgentFrameworkHostingProperties properties, HostingLimits limits, HostingAuthenticator authenticator) {
        return properties.toServerOptions(limits, authenticator);
    }

    @Bean
    @ConditionalOnMissingBean
    HostingJsonCodec agentFrameworkHostingJsonCodec(HostingLimits limits) {
        return new HostingJsonCodec(limits);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    HostingHttpHandler agentFrameworkHostingHttpHandler(
            HostingDispatcher dispatcher, HostingHttpServerOptions options, HostingJsonCodec codec) {
        return new HostingHttpHandler(dispatcher, options, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    SpringHostingHttpHandler agentFrameworkSpringHostingHttpHandler(
            HostingHttpHandler handler, HostingJsonCodec codec, HostingLimits limits) {
        return new SpringHostingHttpHandler(handler, codec, limits);
    }

    @Bean("agentFrameworkHostingRoutes")
    @ConditionalOnMissingBean(name = "agentFrameworkHostingRoutes")
    RouterFunction<ServerResponse> agentFrameworkHostingRoutes(SpringHostingHttpHandler handler) {
        return RouterFunctions.route(
                RequestPredicates.path(HostingHttpHandler.BASE_PATH)
                        .or(RequestPredicates.path(HostingHttpHandler.BASE_PATH + "/**")),
                handler::handle);
    }
}
