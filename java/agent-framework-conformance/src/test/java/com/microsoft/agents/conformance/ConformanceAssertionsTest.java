// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConformanceAssertionsTest {
    @Test
    void assertConforms_shouldSupportFutureImplementationAdapters() {
        // Arrange
        ConformanceFixture fixture =
                new ConformanceFixtureLoader().loadDefault().requireCase("JCF-CORE-001");

        // Act and assert
        assertThatCode(() -> ConformanceAssertions.assertConforms(fixture, ConformanceFixture::expected))
                .doesNotThrowAnyException();
    }

    @Test
    void assertExpected_shouldReportCaseIdWhenActualDataDiffers() {
        // Arrange
        ConformanceFixture fixture =
                new ConformanceFixtureLoader().loadDefault().requireCase("JCF-CORE-001");
        ConformanceValue.ObjectValue actual = new ConformanceValue.ObjectValue(java.util.Map.of());

        // Act and assert
        assertThatThrownBy(() -> ConformanceAssertions.assertExpected(fixture, actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JCF-CORE-001");
    }
}
