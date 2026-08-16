// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class A2AEventValidatorTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void subscription_shouldRequireCurrentTaskAsFirstEvent() {
        // Arrange
        A2AEventValidator validator = new A2AEventValidator(true);

        // Act / Assert
        assertThatThrownBy(() -> validator.accept(new TaskStatusUpdateEvent(
                        "task", "context", status(TaskState.TASK_STATE_WORKING, 1), Map.of())))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("first event");
    }

    @Test
    void validTaskArtifactAndCompletion_shouldPass() {
        // Arrange
        A2AEventValidator validator = new A2AEventValidator(false);
        Artifact first =
                Artifact.builder("artifact").parts(List.of(new TextPart("a"))).build();
        Artifact last =
                Artifact.builder("artifact").parts(List.of(new TextPart("b"))).build();

        // Act / Assert
        assertThatCode(() -> {
                    validator.accept(task(TaskState.TASK_STATE_SUBMITTED, 0));
                    validator.accept(new TaskStatusUpdateEvent(
                            "task", "context", status(TaskState.TASK_STATE_WORKING, 1), Map.of()));
                    validator.accept(new TaskArtifactUpdateEvent("task", "context", first, false, false, Map.of()));
                    validator.accept(new TaskArtifactUpdateEvent("task", "context", last, true, true, Map.of()));
                    validator.accept(new TaskStatusUpdateEvent(
                            "task", "context", status(TaskState.TASK_STATE_COMPLETED, 2), Map.of()));
                    validator.verifyComplete();
                })
                .doesNotThrowAnyException();
    }

    @Test
    void validator_shouldRejectCorrelationChangesAndChunksAfterLast() {
        // Arrange
        A2AEventValidator validator = new A2AEventValidator(false);
        Artifact artifact =
                Artifact.builder("artifact").parts(List.of(new TextPart("a"))).build();
        validator.accept(task(TaskState.TASK_STATE_WORKING, 0));
        validator.accept(new TaskArtifactUpdateEvent("task", "context", artifact, false, true, Map.of()));

        // Act / Assert
        assertThatThrownBy(() ->
                        validator.accept(new TaskArtifactUpdateEvent("task", "other", artifact, true, false, Map.of())))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("correlation");
        assertThatThrownBy(() -> validator.accept(
                        new TaskArtifactUpdateEvent("task", "context", artifact, true, false, Map.of())))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("lastChunk");
    }

    @Test
    void validator_shouldRejectEarlyEndAndEventsAfterTerminal() {
        // Arrange
        A2AEventValidator early = new A2AEventValidator(false);
        early.accept(task(TaskState.TASK_STATE_WORKING, 0));
        A2AEventValidator terminal = new A2AEventValidator(false);
        terminal.accept(task(TaskState.TASK_STATE_COMPLETED, 0));

        // Act / Assert
        assertThatThrownBy(early::verifyComplete)
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("ended before");
        assertThatThrownBy(() -> terminal.accept(new TaskStatusUpdateEvent(
                        "task", "context", status(TaskState.TASK_STATE_COMPLETED, 1), Map.of())))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("after");
    }

    private static Task task(TaskState state, long seconds) {
        return Task.builder("task", "context", status(state, seconds)).build();
    }

    private static TaskStatus status(TaskState state, long seconds) {
        return new TaskStatus(state, START.plusSeconds(seconds));
    }
}
