// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class AgentFrameworkHostingAutoConfigurationTest {
    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentFrameworkHostingAutoConfiguration.class));

    @Test
    void autoConfiguration_shouldRemainDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(HostingRegistry.class);
            assertThat(context).doesNotHaveBean("agentFrameworkHostingRoutes");
        });
    }

    @Test
    void autoConfiguration_shouldCreateOptInWebFluxBeans() {
        contextRunner.withPropertyValues("agent-framework.hosting.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(HostingRegistry.class);
            assertThat(context).hasSingleBean(HostingDispatcher.class);
            assertThat(context).hasSingleBean(HostingAuthenticator.class);
            assertThat(context).hasBean("agentFrameworkHostingRoutes");
            assertThat(context.getStartupFailure()).isNull();
        });
    }

    @Test
    void autoConfiguration_shouldBackOffForApplicationIdentityAndRegistryBeans() {
        HostingRegistry registry = new HostingRegistry();
        HostingAuthenticator authenticator = request -> CompletableFuture.completedFuture(
                HostingAuthentication.authenticated(new HostingPrincipal("application", "tenant")));

        contextRunner
                .withPropertyValues("agent-framework.hosting.enabled=true")
                .withBean(HostingRegistry.class, () -> registry)
                .withBean(HostingAuthenticator.class, () -> authenticator)
                .run(context -> {
                    assertThat(context.getBean(HostingRegistry.class)).isSameAs(registry);
                    assertThat(context.getBean(HostingAuthenticator.class)).isSameAs(authenticator);
                    assertThat(context).hasSingleBean(HostingDispatcher.class);
                });
    }

    @Test
    void autoConfiguration_shouldFailClosedForRemoteBindingWithLocalIdentity() {
        contextRunner
                .withPropertyValues(
                        "agent-framework.hosting.enabled=true",
                        "agent-framework.hosting.bind-address=0.0.0.0",
                        "agent-framework.hosting.trusted-tls-proxy=true",
                        "agent-framework.hosting.advertised-endpoint=https://agents.example",
                        "agent-framework.hosting.allowed-hosts[0]=agents.example",
                        "agent-framework.hosting.allowed-origins[0]=https://app.example")
                .run(context -> assertThat(context).hasFailed());
    }
}
