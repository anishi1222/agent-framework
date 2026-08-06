// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrchestrationContractsTest {
    @Test
    void participantDescriptors_shouldUseStableIdsAndDefensiveOrdering() {
        // Arrange
        TestAgent first = TestAgent.responding("first", "one");
        TestAgent second = TestAgent.responding("second", "two");
        ArrayList<OrchestrationParticipant> source =
                new ArrayList<>(List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)));
        SequentialOrchestration orchestration =
                SequentialOrchestration.builder(source).build();

        // Act
        source.clear();

        // Assert
        assertThat(orchestration.participants())
                .extracting(OrchestrationParticipant::id)
                .containsExactly("first", "second");
        assertThatThrownBy(() -> orchestration.participants().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        orchestration.close();
        first.close();
        second.close();
    }

    @Test
    void builders_shouldRejectDuplicateIdsAndAgentInstances() {
        // Arrange
        TestAgent first = TestAgent.responding("same", "one");
        TestAgent second = TestAgent.responding("same", "two");

        // Act / Assert
        assertThatThrownBy(() -> SequentialOrchestration.builder(
                                List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate participant id");
        assertThatThrownBy(() -> SequentialOrchestration.builder(List.of(
                                new OrchestrationParticipant("one", first), new OrchestrationParticipant("two", first)))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("same agent instance");

        first.close();
        second.close();
    }

    @Test
    void runOptions_shouldDefensivelyCopyMetadataAndValidateBounds() {
        // Arrange
        java.util.LinkedHashMap<String, StateValue> metadata = new java.util.LinkedHashMap<>();
        metadata.put("key", StateValue.string("value"));

        // Act
        OrchestrationRunOptions options =
                OrchestrationRunOptions.builder().metadata(metadata).build();
        metadata.clear();

        // Assert
        assertThat(options.metadata()).isEqualTo(Map.of("key", StateValue.string("value")));
        assertThatThrownBy(() -> OrchestrationRunOptions.builder().maxBufferedEvents(0))
                .isInstanceOf(IllegalArgumentException.class);
        TestAgent temporary = TestAgent.responding("temporary", "value");
        assertThatThrownBy(() -> HandoffOrchestration.builder(List.of(OrchestrationParticipant.of(temporary)))
                        .maxHandoffs(-1))
                .isInstanceOf(IllegalArgumentException.class);
        temporary.close();
    }
}
