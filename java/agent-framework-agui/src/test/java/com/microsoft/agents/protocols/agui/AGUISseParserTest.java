// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AGUISseParserTest {
    @Test
    void parser_shouldConsumeCommentsCrLfAndMultilineDataIncrementally() {
        // Arrange
        AGUISseParser parser = new AGUISseParser(new AGUIJsonCodec(AGUILimits.defaults()));

        // Act
        assertThat(parser.acceptLine(": heartbeat")).isEmpty();
        assertThat(parser.acceptLine("")).isEmpty();
        assertThat(parser.acceptLine("data: {\"type\":\"RUN_ERROR\",")).isEmpty();
        assertThat(parser.acceptLine("data: \"message\":\"failed\"}")).isEmpty();
        List<AGUIEvent> events = parser.acceptLine("");

        // Assert
        assertThat(events).containsExactly(new AGUIEvents.RunError("failed", null, null, null));
        assertThat(parser.finish()).isEmpty();
    }

    @Test
    void parser_shouldRejectReplayAndReconnectFields() {
        // Arrange
        AGUISseParser parser = new AGUISseParser(new AGUIJsonCodec(AGUILimits.defaults()));

        // Act and assert
        assertThatThrownBy(() -> parser.acceptLine("id: 42")).isInstanceOf(AGUIProtocolException.class);
        assertThatThrownBy(() -> parser.acceptLine("retry: 1000")).isInstanceOf(AGUIProtocolException.class);
    }
}
