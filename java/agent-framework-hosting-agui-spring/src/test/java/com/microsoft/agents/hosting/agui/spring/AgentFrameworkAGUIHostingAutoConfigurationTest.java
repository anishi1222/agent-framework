// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.hosting.agui.AGUIHostingHttpHandler;
import com.microsoft.agents.hosting.agui.AGUIHostingRegistry;
import com.microsoft.agents.hosting.agui.AGUIPrincipalResolver;
import com.microsoft.agents.hosting.agui.AGUIThreadStore;
import com.microsoft.agents.hosting.spring.AgentFrameworkHostingAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class AgentFrameworkAGUIHostingAutoConfigurationTest {
    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentFrameworkHostingAutoConfiguration.class, AgentFrameworkAGUIHostingAutoConfiguration.class));

    @Test
    void autoConfiguration_shouldRemainDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AGUIHostingRegistry.class);
            assertThat(context).doesNotHaveBean("agentFrameworkAGUIHostingRoutes");
        });
    }

    @Test
    void autoConfiguration_shouldReuseOptInGenericWebFluxBeans() {
        contextRunner
                .withPropertyValues("agent-framework.hosting.enabled=true", "agent-framework.hosting.agui.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AGUIHostingRegistry.class);
                    assertThat(context).hasSingleBean(AGUIHostingHttpHandler.class);
                    assertThat(context).hasSingleBean(AGUIThreadStore.class);
                    assertThat(context).hasSingleBean(AGUIPrincipalResolver.class);
                    assertThat(context).hasBean("agentFrameworkAGUIHostingRoutes");
                    assertThat(context.getStartupFailure()).isNull();
                });
    }
}
