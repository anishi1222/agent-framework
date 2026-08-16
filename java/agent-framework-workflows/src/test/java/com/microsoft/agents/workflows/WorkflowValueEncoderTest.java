// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowValueEncoderTest {
    @Test
    void defaultEncoder_shouldEncodeJsonShapedValuesDeterministically() {
        // Arrange
        WorkflowValueEncoder encoder = WorkflowValueEncoder.defaultEncoder();

        // Act
        StateValue encoded = encoder.encode(Map.of(
                "array", List.of("value", true, 3L),
                "decimal", new BigDecimal("1.25"),
                "stateValue", StateValue.string("already-encoded")));

        // Assert
        assertThat(encoded)
                .isEqualTo(StateValue.object(Map.of(
                        "array",
                        StateValue.array(
                                List.of(StateValue.string("value"), StateValue.bool(true), StateValue.integer(3))),
                        "decimal",
                        StateValue.number(new BigDecimal("1.25")),
                        "stateValue",
                        StateValue.string("already-encoded"))));
    }

    @Test
    void defaultEncoder_shouldRejectUnsupportedTypesInsteadOfUsingClassNameFallback() {
        // Arrange
        CustomValue value = new CustomValue("unsupported");

        // Act / Assert
        assertThatThrownBy(() -> WorkflowValueEncoder.defaultEncoder().encode(value))
                .isInstanceOf(WorkflowValueEncodingException.class)
                .hasMessageContaining(CustomValue.class.getName())
                .hasMessageContaining("valueEncoder");
    }

    @Test
    void runOptions_shouldUseCustomEncoderForStronglyTypedWorkflowValues() {
        // Arrange
        WorkflowBuilder<CustomValue, CustomValue> builder =
                WorkflowBuilder.create("custom-values", CustomValue.class, CustomValue.class);
        WorkflowNode<CustomValue, CustomValue> node = builder.addNode(
                "node",
                FunctionExecutor.sync(
                        CustomValue.class,
                        CustomValue.class,
                        (value, context) -> new CustomValue(value.text() + "-encoded")));
        WorkflowValueEncoder defaults = WorkflowValueEncoder.defaultEncoder();
        WorkflowValueEncoder custom = value -> {
            if (value instanceof CustomValue customValue) {
                return StateValue.object(Map.of("text", StateValue.string(customValue.text())));
            }
            return defaults.encode(value);
        };

        // Act
        try (Workflow<CustomValue, CustomValue> workflow =
                builder.entry(node).output(node).build()) {
            WorkflowRunResult<CustomValue> result = workflow.run(
                    new CustomValue("custom"),
                    WorkflowRunOptions.builder().valueEncoder(custom).build());

            // Assert
            assertThat(result.output()).isEqualTo(new CustomValue("custom-encoded"));
        }
    }

    private record CustomValue(String text) {}
}
