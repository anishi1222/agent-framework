// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AGUIEventStreamValidatorTest {
    @Test
    void validator_shouldAcceptBalancedCorrelatedRunAndApplyStateAndActivityPatches() {
        // Arrange
        AGUIEventStreamValidator validator = new AGUIEventStreamValidator(
                AGUILimits.defaults(),
                new AGUIValidationContext(java.util.Set.of(), java.util.Set.of(), StateValue.object(Map.of())));
        List<AGUIEvent> events = validEvents();

        // Act
        events.forEach(validator::accept);
        validator.finish();

        // Assert
        assertThat(validator.eventCount()).isEqualTo(events.size());
        assertThat(validator.currentState()).isEqualTo(StateValue.object(Map.of("status", StateValue.string("done"))));
    }

    @Test
    void validator_shouldRejectMissingStartDuplicateWrongTargetsUnbalancedAndAfterTerminal() {
        // Arrange
        AGUIEvent runStart = new AGUIEvents.RunStarted("thread", "run", null, null, null, null);

        // Act and assert
        assertThatThrownBy(() -> new AGUIEventStreamValidator(AGUILimits.defaults())
                        .accept(new AGUIEvents.RunError("bad", null, null, null)))
                .isInstanceOf(AGUIProtocolException.class);

        AGUIEventStreamValidator wrongText = new AGUIEventStreamValidator(AGUILimits.defaults());
        wrongText.accept(runStart);
        wrongText.accept(new AGUIEvents.TextMessageStart("message", AGUIRole.ASSISTANT, null, null, null));
        assertThatThrownBy(() -> wrongText.accept(new AGUIEvents.TextMessageContent("other", "x", null, null)))
                .isInstanceOf(AGUIProtocolException.class);

        AGUIEventStreamValidator duplicateTool = new AGUIEventStreamValidator(AGUILimits.defaults());
        duplicateTool.accept(runStart);
        duplicateTool.accept(new AGUIEvents.ToolCallStart("call", "tool", null, null, null));
        duplicateTool.accept(new AGUIEvents.ToolCallEnd("call", null, null));
        assertThatThrownBy(() -> duplicateTool.accept(new AGUIEvents.ToolCallStart("call", "tool", null, null, null)))
                .isInstanceOf(AGUIProtocolException.class);

        AGUIEventStreamValidator unbalanced = new AGUIEventStreamValidator(AGUILimits.defaults());
        unbalanced.accept(runStart);
        unbalanced.accept(new AGUIEvents.StepStarted("step", null, null));
        assertThatThrownBy(() -> unbalanced.accept(
                        new AGUIEvents.RunFinished("thread", "run", null, new AGUIRunOutcomes.Success(), null, null)))
                .isInstanceOf(AGUIProtocolException.class);

        AGUIEventStreamValidator afterTerminal = new AGUIEventStreamValidator(AGUILimits.defaults());
        afterTerminal.accept(runStart);
        afterTerminal.accept(new AGUIEvents.RunError("bad", null, null, null));
        assertThatThrownBy(
                        () -> afterTerminal.accept(new AGUIEvents.Custom("late", StateValue.nullValue(), null, null)))
                .isInstanceOf(AGUIProtocolException.class);
    }

    @Test
    void validator_shouldAllowPriorToolResultOnResumeButRejectUnknownResultAndDelta() {
        // Arrange
        AGUIEventStreamValidator resumed = new AGUIEventStreamValidator(
                AGUILimits.defaults(),
                new AGUIValidationContext(java.util.Set.of(), java.util.Set.of("prior-call"), null));
        resumed.accept(new AGUIEvents.RunStarted("thread", "run-2", null, null, null, null));

        // Act
        resumed.accept(new AGUIEvents.ToolCallResult("result", "prior-call", "ok", AGUIRole.TOOL, null, null));
        resumed.accept(new AGUIEvents.RunFinished("thread", "run-2", null, new AGUIRunOutcomes.Success(), null, null));
        resumed.finish();

        // Assert
        AGUIEventStreamValidator invalid = new AGUIEventStreamValidator(AGUILimits.defaults());
        invalid.accept(new AGUIEvents.RunStarted("thread", "run", null, null, null, null));
        assertThatThrownBy(() ->
                        invalid.accept(new AGUIEvents.ToolCallResult("result", "missing", "bad", null, null, null)))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() -> invalid.accept(new AGUIEvents.StateDelta(
                        List.of(new AGUIJsonPatchOperation(
                                AGUIJsonPatchOperation.Operation.ADD, "/x", null, StateValue.integer(1))),
                        null,
                        null)))
                .isInstanceOf(AGUIProtocolException.class);
    }

    private static List<AGUIEvent> validEvents() {
        List<AGUIJsonPatchOperation> statePatch = List.of(new AGUIJsonPatchOperation(
                AGUIJsonPatchOperation.Operation.ADD, "/status", null, StateValue.string("done")));
        return List.of(
                new AGUIEvents.RunStarted("thread", "run", null, null, null, null),
                new AGUIEvents.StepStarted("work", null, null),
                new AGUIEvents.TextMessageStart("message", AGUIRole.ASSISTANT, null, null, null),
                new AGUIEvents.TextMessageContent("message", "hello", null, null),
                new AGUIEvents.TextMessageEnd("message", null, null),
                new AGUIEvents.ToolCallStart("call", "search", "message", null, null),
                new AGUIEvents.ToolCallArgs("call", "{}", null, null),
                new AGUIEvents.ToolCallEnd("call", null, null),
                new AGUIEvents.ToolCallResult("result", "call", "ok", AGUIRole.TOOL, null, null),
                new AGUIEvents.StateDelta(statePatch, null, null),
                new AGUIEvents.ActivitySnapshot("activity", "PLAN", StateValue.object(Map.of()), true, null, null),
                new AGUIEvents.ActivityDelta("activity", "PLAN", statePatch, null, null),
                new AGUIEvents.ReasoningStart("phase", null, null),
                new AGUIEvents.ReasoningMessageStart("reasoning", AGUIRole.REASONING, null, null),
                new AGUIEvents.ReasoningMessageContent("reasoning", "summary", null, null),
                new AGUIEvents.ReasoningMessageEnd("reasoning", null, null),
                new AGUIEvents.ReasoningEncryptedValue(
                        AGUIReasoningEncryptedSubtype.MESSAGE, "reasoning", "opaque", null, null),
                new AGUIEvents.ReasoningEnd("phase", null, null),
                new AGUIEvents.StepFinished("work", null, null),
                new AGUIEvents.RunFinished("thread", "run", null, new AGUIRunOutcomes.Success(), null, null));
    }
}
