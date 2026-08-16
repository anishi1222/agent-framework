// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConformanceFixtureValidationTest {
    private final ConformanceFixtureLoader loader = new ConformanceFixtureLoader();

    @TestFactory
    Stream<DynamicTest> everyRegisteredFixture_shouldLoadAndExposeExpectedData() {
        // Arrange
        ConformanceFixtureCatalog catalog = loader.loadDefault();

        // Act
        return catalog.cases().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    ConformanceFixture fixture = entry.getValue();

                    // Assert
                    assertThat(fixture.caseId()).isEqualTo(entry.getKey());
                    assertThat(fixture.schemaVersion()).isEqualTo(1);
                    assertThat(fixture.description()).isNotBlank();
                    assertThat(fixture.expected().values()).isNotEmpty();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    void loadFixture_shouldRejectInvalidSchema(String name, String json, String expectedMessage) {
        // Arrange
        ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        // Act and assert
        assertThatThrownBy(() -> loader.loadFixture(input, name))
                .isInstanceOf(ConformanceValidationException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidFixtures() {
        return Stream.of(
                Arguments.of("unknown-version", """
                        {"schemaVersion":2,"caseId":"JCF-CORE-999","kind":"contract",
                         "description":"invalid","input":{},"expected":{"valid":false}}
                        """, "unsupported schemaVersion"),
                Arguments.of("unknown-kind", """
                        {"schemaVersion":1,"caseId":"JCF-CORE-999","kind":"javaClass",
                         "description":"invalid","input":{},"expected":{"valid":false}}
                        """, "Unknown fixture kind"),
                Arguments.of("unknown-field", """
                        {"schemaVersion":1,"caseId":"JCF-CORE-999","kind":"contract",
                         "description":"invalid","input":{},"expected":{"valid":false},"@class":"example.Bad"}
                        """, "unknown field '@class'"),
                Arguments.of("duplicate-key", """
                        {"schemaVersion":1,"schemaVersion":1,"caseId":"JCF-CORE-999","kind":"contract",
                         "description":"invalid","input":{},"expected":{"valid":false}}
                        """, "Invalid JSON"),
                Arguments.of("trailing-content", """
                        {"schemaVersion":1,"caseId":"JCF-CORE-999","kind":"contract",
                         "description":"invalid","input":{},"expected":{"valid":false}} {}
                        """, "trailing JSON content"),
                Arguments.of("empty-events", """
                        {"schemaVersion":1,"caseId":"JCF-CORE-999","kind":"tool-loop",
                         "description":"invalid","events":[],"expected":{"valid":false}}
                        """, "must not be empty"));
    }
}
