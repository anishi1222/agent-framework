// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AGUIJsonPatchTest {
    @Test
    void apply_shouldSupportAllRfcOperationsWithoutMutatingSource() {
        // Arrange
        StateValue source = StateValue.object(Map.of(
                "items",
                StateValue.array(List.of(StateValue.string("a"), StateValue.string("b"))),
                "copy",
                StateValue.string("value")));
        List<AGUIJsonPatchOperation> operations = List.of(
                operation("test", "/copy", null, StateValue.string("value")),
                operation("add", "/items/-", null, StateValue.string("c")),
                operation("replace", "/items/0", null, StateValue.string("A")),
                operation("copy", "/copied", "/copy", null),
                operation("move", "/moved", "/items/1", null),
                operation("remove", "/copy", null, null));

        // Act
        StateValue result = AGUIJsonPatch.apply(source, operations);

        // Assert
        assertThat(result)
                .isEqualTo(StateValue.object(Map.of(
                        "items",
                        StateValue.array(List.of(StateValue.string("A"), StateValue.string("c"))),
                        "copied",
                        StateValue.string("value"),
                        "moved",
                        StateValue.string("b"))));
        assertThat(((StateValue.ObjectValue) source).values()).containsKey("copy");
    }

    @Test
    void patch_shouldRejectUnsafePointersInvalidIndexesAndFailedTests() {
        // Arrange
        StateValue source = StateValue.object(Map.of("array", StateValue.array(List.of(StateValue.string("x")))));

        // Act and assert
        assertThatThrownBy(() -> operation("add", "/__proto__/polluted", null, StateValue.bool(true)))
                .isInstanceOf(AGUIProtocolException.class)
                .extracting("code")
                .isEqualTo(AGUIErrorCode.INVALID_PATCH);
        assertThatThrownBy(() -> AGUIJsonPatch.apply(
                        source, List.of(operation("replace", "/array/01", null, StateValue.string("bad")))))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() -> AGUIJsonPatch.apply(
                        source, List.of(operation("test", "/array/0", null, StateValue.string("wrong")))))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() ->
                        AGUIJsonPatch.apply(source, List.of(operation("move", "/array/0/child", "/array/0", null))))
                .isInstanceOf(AGUIProtocolException.class);
    }

    private static AGUIJsonPatchOperation operation(String op, String path, String from, StateValue value) {
        return new AGUIJsonPatchOperation(AGUIJsonPatchOperation.Operation.fromValue(op), path, from, value);
    }
}
