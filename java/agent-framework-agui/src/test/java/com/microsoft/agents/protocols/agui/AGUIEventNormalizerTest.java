// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AGUIEventNormalizerTest {
    @Test
    void chunks_shouldNormalizeToBalancedExplicitLifecycles() {
        // Arrange
        AGUIEventNormalizer normalizer = new AGUIEventNormalizer();
        ArrayList<AGUIEvent> events = new ArrayList<>();

        // Act
        events.addAll(normalizer.accept(
                new AGUIEvents.TextMessageChunk("message-1", AGUIRole.ASSISTANT, "hel", null, null, null)));
        events.addAll(normalizer.accept(new AGUIEvents.TextMessageChunk(null, null, "lo", null, null, null)));
        events.addAll(
                normalizer.accept(new AGUIEvents.ToolCallChunk("call-1", "search", "message-1", "{", null, null)));
        events.addAll(normalizer.accept(new AGUIEvents.ToolCallChunk(null, null, null, "}", null, null)));
        events.addAll(normalizer.accept(new AGUIEvents.ReasoningMessageChunk("reasoning-1", "summary", null, null)));
        events.addAll(normalizer.accept(new AGUIEvents.ReasoningMessageChunk(null, "", null, null)));
        events.addAll(normalizer.finish());

        // Assert
        assertThat(events)
                .extracting(AGUIEvent::type)
                .containsExactly(
                        AGUIEventType.TEXT_MESSAGE_START,
                        AGUIEventType.TEXT_MESSAGE_CONTENT,
                        AGUIEventType.TEXT_MESSAGE_CONTENT,
                        AGUIEventType.TEXT_MESSAGE_END,
                        AGUIEventType.TOOL_CALL_START,
                        AGUIEventType.TOOL_CALL_ARGS,
                        AGUIEventType.TOOL_CALL_ARGS,
                        AGUIEventType.TOOL_CALL_END,
                        AGUIEventType.REASONING_MESSAGE_START,
                        AGUIEventType.REASONING_MESSAGE_CONTENT,
                        AGUIEventType.REASONING_MESSAGE_END);
    }

    @Test
    void chunk_shouldRejectMissingFirstIdentifierOrChangedMetadata() {
        // Arrange
        AGUIEventNormalizer normalizer = new AGUIEventNormalizer();

        // Act and assert
        assertThatThrownBy(() -> normalizer.accept(new AGUIEvents.TextMessageChunk(null, null, "x", null, null, null)))
                .isInstanceOf(AGUIProtocolException.class);

        normalizer.accept(new AGUIEvents.ToolCallChunk("call-1", "one", null, null, null, null));
        assertThatThrownBy(() -> normalizer.accept(new AGUIEvents.ToolCallChunk(null, "two", null, null, null, null)))
                .isInstanceOf(AGUIProtocolException.class);
    }
}
