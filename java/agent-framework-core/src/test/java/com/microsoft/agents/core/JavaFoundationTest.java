// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class JavaFoundationTest {
    @Test
    void testRuntime_shouldUseJava25_andProvideConfiguredTestLibraries() {
        // Arrange
        TestProbe probe = mock(TestProbe.class);
        when(probe.value()).thenReturn("ready");

        // Act
        int runtimeFeature = Runtime.version().feature();

        // Assert
        assertThat(runtimeFeature).isEqualTo(25);
        assertThat(probe.value()).isEqualTo("ready");
    }

    private interface TestProbe {
        String value();
    }
}
