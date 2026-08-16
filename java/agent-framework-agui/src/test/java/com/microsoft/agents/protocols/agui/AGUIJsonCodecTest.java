// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AGUIJsonCodecTest {
    private final AGUIJsonCodec codec = new AGUIJsonCodec(AGUILimits.defaults());

    @ParameterizedTest
    @MethodSource("allEvents")
    void event_shouldRoundTripEveryOfficialType(AGUIEvent event) {
        // Act
        byte[] encoded = codec.encodeEvent(event);
        AGUIEvent decoded = codec.decodeEvent(encoded);

        // Assert
        assertThat(decoded).isEqualTo(event);
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .startsWith("{\"type\":\"" + event.type().name() + "\"");
    }

    @Test
    void runAgentInput_shouldRoundTripExactMessagesToolsContextAndResume() {
        // Arrange
        RunAgentInput input = input();

        // Act
        byte[] encoded = codec.encodeRunAgentInput(input);
        RunAgentInput decoded = codec.decodeRunAgentInput(encoded);

        // Assert
        assertThat(decoded).isEqualTo(input);
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .contains(
                        "\"threadId\":\"thread-1\"",
                        "\"parentRunId\":\"run-parent\"",
                        "\"forwardedProps\"",
                        "\"resume\"");
    }

    @Test
    void strictJson_shouldRejectDuplicateTrailingNonfiniteUnknownAndLimits() {
        // Arrange
        AGUIJsonCodec shallow = new AGUIJsonCodec(new AGUILimits(1024, 1024, 2, 32, 8, 4, 4, 512, 10, 4));

        // Act and assert
        assertThatThrownBy(() ->
                        codec.decodeEvent(bytes("{\"type\":\"RUN_ERROR\",\"message\":\"one\",\"message\":\"two\"}")))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() -> codec.decodeEvent(bytes("{\"type\":\"RUN_ERROR\",\"message\":\"bad\"} trailing")))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() -> codec.decodeEvent(bytes("{\"type\":\"CUSTOM\",\"name\":\"x\",\"value\":NaN}")))
                .isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(
                        () -> codec.decodeEvent(bytes("{\"type\":\"META_EVENT\",\"metaType\":\"x\",\"payload\":{}}")))
                .isInstanceOf(AGUIProtocolException.class)
                .extracting("code")
                .isEqualTo(AGUIErrorCode.UNKNOWN_EVENT);
        assertThatThrownBy(() -> shallow.decodeValue(bytes("{\"a\":{\"b\":{\"c\":1}}}")))
                .isInstanceOf(AGUIProtocolException.class)
                .extracting("code")
                .isEqualTo(AGUIErrorCode.LIMIT_EXCEEDED);
    }

    @Test
    void inputPolicy_shouldRejectBlankOptionalAndUnknownRunInputMembersWithActionableErrors() {
        // Act and assert
        assertThatThrownBy(() -> codec.decodeEvent(bytes("{\"type\":\"TEXT_MESSAGE_START\",\"messageId\":\"message-1\","
                        + "\"role\":\"assistant\",\"name\":\" \"}")))
                .isInstanceOf(AGUIProtocolException.class)
                .hasMessageContaining("name must not be blank");
        assertThatThrownBy(() -> new RunAgentInput(
                        "thread",
                        "run",
                        " ",
                        StateValue.object(Map.of()),
                        List.of(),
                        List.of(),
                        List.of(),
                        StateValue.object(Map.of()),
                        List.of()))
                .isInstanceOf(AGUIProtocolException.class)
                .hasMessageContaining("parentRunId must not be blank");
        assertThatThrownBy(() -> codec.decodeRunAgentInput(
                        bytes("{\"threadId\":\"thread\",\"runId\":\"run\",\"state\":{},\"messages\":[],"
                                + "\"tools\":[],\"context\":[],\"forwardedProps\":{},\"futureOption\":true}")))
                .isInstanceOf(AGUIProtocolException.class)
                .hasMessageContaining("unsupported members [futureOption]")
                .hasMessageContaining("remove them or upgrade");
    }

    @Test
    void eventEnvelope_shouldRoundTripUnknownAdditiveField() {
        // Arrange
        byte[] wire = bytes("{\"type\":\"RUN_ERROR\",\"message\":\"failed\",\"futureField\":{\"enabled\":true}}");

        // Act
        AGUIEvent event = codec.decodeEvent(wire);
        byte[] encoded = codec.encodeEvent(event);

        // Assert
        assertThat(event.additionalProperties()).containsOnlyKeys("futureField");
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"RUN_ERROR\",\"message\":\"failed\"," + "\"futureField\":{\"enabled\":true}}");
    }

    @Test
    void sseFrame_shouldMatchOfficialEncoderJsonForm() {
        // Arrange
        AGUIEvent event = new AGUIEvents.TextMessageContent("message-1", "hello", null, null);

        // Act
        String frame = new String(codec.encodeSseFrame(event), StandardCharsets.UTF_8);

        // Assert
        assertThat(frame)
                .isEqualTo("data: {\"type\":\"TEXT_MESSAGE_CONTENT\","
                        + "\"messageId\":\"message-1\",\"delta\":\"hello\"}\n\n");
    }

    @SuppressWarnings("removal")
    static Stream<AGUIEvent> allEvents() {
        StateValue.ObjectValue object = StateValue.object(Map.of("value", StateValue.string("x")));
        List<AGUIJsonPatchOperation> patch = List.of(new AGUIJsonPatchOperation(
                AGUIJsonPatchOperation.Operation.ADD, "/value", null, StateValue.string("y")));
        AGUIMessage message = new AGUIMessages.User("user-1", new AGUIMessages.TextUserContent("hello"), null, null);
        return Stream.of(
                new AGUIEvents.TextMessageStart("message-1", AGUIRole.ASSISTANT, null, null, null),
                new AGUIEvents.TextMessageContent("message-1", "hello", null, null),
                new AGUIEvents.TextMessageEnd("message-1", null, null),
                new AGUIEvents.TextMessageChunk("message-1", AGUIRole.ASSISTANT, "hello", null, null, null),
                new AGUIEvents.ToolCallStart("call-1", "search", "message-1", null, null),
                new AGUIEvents.ToolCallArgs("call-1", "{}", null, null),
                new AGUIEvents.ToolCallEnd("call-1", null, null),
                new AGUIEvents.ToolCallChunk("call-1", "search", null, "{}", null, null),
                new AGUIEvents.ToolCallResult("tool-1", "call-1", "ok", AGUIRole.TOOL, null, null),
                new AGUIEvents.ThinkingStart("thinking", null, null),
                new AGUIEvents.ThinkingEnd(null, null),
                new AGUIEvents.ThinkingTextMessageStart(null, null),
                new AGUIEvents.ThinkingTextMessageContent("legacy", null, null),
                new AGUIEvents.ThinkingTextMessageEnd(null, null),
                new AGUIEvents.StateSnapshot(object, null, null),
                new AGUIEvents.StateDelta(patch, null, null),
                new AGUIEvents.MessagesSnapshot(List.of(message), null, null),
                new AGUIEvents.ActivitySnapshot("activity-1", "PLAN", object, true, null, null),
                new AGUIEvents.ActivityDelta("activity-1", "PLAN", patch, null, null),
                new AGUIEvents.Raw(object, "provider", null, null),
                new AGUIEvents.Custom("example/event", object, null, null),
                new AGUIEvents.RunStarted("thread-1", "run-1", null, null, null, null),
                new AGUIEvents.RunFinished("thread-1", "run-1", object, new AGUIRunOutcomes.Success(), null, null),
                new AGUIEvents.RunError("failed", "E_TEST", null, null),
                new AGUIEvents.StepStarted("step", null, null),
                new AGUIEvents.StepFinished("step", null, null),
                new AGUIEvents.ReasoningStart("reasoning-1", null, null),
                new AGUIEvents.ReasoningMessageStart("reasoning-message-1", AGUIRole.REASONING, null, null),
                new AGUIEvents.ReasoningMessageContent("reasoning-message-1", "summary", null, null),
                new AGUIEvents.ReasoningMessageEnd("reasoning-message-1", null, null),
                new AGUIEvents.ReasoningMessageChunk("reasoning-message-1", "summary", null, null),
                new AGUIEvents.ReasoningEnd("reasoning-1", null, null),
                new AGUIEvents.ReasoningEncryptedValue(
                        AGUIReasoningEncryptedSubtype.MESSAGE, "reasoning-message-1", "opaque", null, null));
    }

    private static RunAgentInput input() {
        StateValue.ObjectValue schema = StateValue.object(Map.of("type", StateValue.string("object")));
        return new RunAgentInput(
                "thread-1",
                "run-1",
                "run-parent",
                StateValue.object(Map.of("count", StateValue.integer(1))),
                List.of(
                        new AGUIMessages.Developer("developer-1", "rules", null, null),
                        new AGUIMessages.System("system-1", "system", null, null),
                        new AGUIMessages.User(
                                "user-1",
                                new AGUIMessages.PartsUserContent(List.of(
                                        new AGUIMessages.TextInput("hello"),
                                        new AGUIMessages.MediaInput(
                                                AGUIMediaKind.IMAGE,
                                                new AGUIMessages.UrlSource(
                                                        "https://example.test/image.png", "image/png"),
                                                StateValue.object(Map.of())))),
                                "user",
                                null),
                        new AGUIMessages.Assistant(
                                "assistant-1",
                                null,
                                null,
                                null,
                                List.of(new AGUIMessages.ToolCall(
                                        "call-1", new AGUIMessages.FunctionCall("search", "{}"), null))),
                        new AGUIMessages.Tool("tool-1", "result", "call-1", null, null),
                        new AGUIMessages.Activity("activity-1", "PLAN", StateValue.object(Map.of())),
                        new AGUIMessages.Reasoning("reasoning-1", "summary", "opaque")),
                List.of(new AGUITool("clientTool", "Client tool", schema)),
                List.of(new AGUIContext("page", "home")),
                StateValue.object(Map.of("tenantHint", StateValue.string("display-only"))),
                List.of(new AGUIResumeEntry(
                        "interrupt-1",
                        AGUIResumeStatus.RESOLVED,
                        StateValue.object(Map.of("approved", StateValue.bool(true))))));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
