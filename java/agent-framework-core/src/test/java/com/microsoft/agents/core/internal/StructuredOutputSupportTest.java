// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputSupportTest {
    private static final StateValue.ObjectValue SCHEMA = StateValue.object(Map.of("type", StateValue.string("object")));

    @Test
    void resolve_shouldSupportNeutralAndLegacySchemaButRejectAmbiguousConfiguration() {
        StructuredOutputOptions neutral = StructuredOutputOptions.jsonSchema("answer", SCHEMA.values());

        assertThat(StructuredOutputSupport.resolve(
                        ChatOptions.builder().structuredOutput(neutral).build(), "provider.responseSchema"))
                .isSameAs(neutral);
        assertThat(StructuredOutputSupport.resolve(
                        ChatOptions.builder()
                                .metadata(Map.of("provider.responseSchema", SCHEMA))
                                .build(),
                        "provider.responseSchema"))
                .satisfies(resolved -> {
                    assertThat(resolved.name()).isEqualTo("response");
                    assertThat(resolved.schema()).isEqualTo(SCHEMA);
                    assertThat(resolved.strict()).isTrue();
                });
        assertThatThrownBy(() -> StructuredOutputSupport.resolve(
                        ChatOptions.builder()
                                .structuredOutput(neutral)
                                .metadata(Map.of("provider.responseSchema", SCHEMA))
                                .build(),
                        "provider.responseSchema"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not both");
    }
}
