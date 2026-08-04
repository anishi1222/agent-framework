// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConformanceValueTest {
    @ParameterizedTest(name = "{0} is mathematically equal to {1}")
    @MethodSource("equivalentNumbers")
    void numberValue_shouldUseMathematicalEqualityAndHashing(String left, String right) {
        // Arrange
        ConformanceValue.NumberValue leftValue =
                new ConformanceValue.NumberValue(new BigDecimal(left));
        ConformanceValue.NumberValue rightValue =
                new ConformanceValue.NumberValue(new BigDecimal(right));

        // Act and assert
        assertThat(leftValue).isEqualTo(rightValue);
        assertThat(leftValue.hashCode()).isEqualTo(rightValue.hashCode());
    }

    private static Stream<Arguments> equivalentNumbers() {
        return Stream.of(
                Arguments.of("1", "1.0"),
                Arguments.of("0.0", "0E+10"),
                Arguments.of("1E+3", "1000.000"),
                Arguments.of("-2.5000", "-2.5"));
    }
}
