// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.agui.community.core.event.EventType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AGUICommunityJavaCompatibilityTest {
    @Test
    @SuppressWarnings("removal")
    void communityJava010_shouldMatchCurrentNonDeprecatedEventsExceptDraftMetaEvent() {
        // Arrange
        Set<String> framework = Arrays.stream(AGUIEventType.values())
                .map(Enum::name)
                .filter(name -> !name.startsWith("THINKING_"))
                .collect(Collectors.toSet());
        Set<String> community = Arrays.stream(EventType.values())
                .map(Enum::name)
                .filter(name -> !"META_EVENT".equals(name))
                .collect(Collectors.toSet());

        // Act and assert
        assertThat(community).containsExactlyInAnyOrderElementsOf(framework);
        assertThat(Arrays.stream(EventType.values()).map(Enum::name)).contains("META_EVENT");
        assertThat(Arrays.stream(com.agui.community.core.agent.RunAgentInput.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("parentRunId");
        assertThat(Arrays.stream(RunAgentInput.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .contains("parentRunId");
    }
}
