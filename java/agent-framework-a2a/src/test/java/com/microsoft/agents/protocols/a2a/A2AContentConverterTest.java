// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class A2AContentConverterTest {
    private final A2AJsonCodec codec = new A2AJsonCodec(A2ALimits.defaults());

    @Test
    void parityFixture_shouldRoundTripTextUriBytesDataMimeAndMetadata() {
        // Arrange
        com.microsoft.agents.core.Message framework = new com.microsoft.agents.core.Message(
                com.microsoft.agents.core.Role.USER,
                List.of(
                        new TextContent("hello", Map.of("language", StateValue.string("en"))),
                        new UriContent(
                                URI.create("https://files.test/image.png"),
                                "image/png",
                                Map.of("source", StateValue.string("python-dotnet-fixture"))),
                        new DataContent(
                                new byte[] {1, 2, 3},
                                "audio/wav",
                                Map.of("a2a.filename", StateValue.string("clip.wav"))),
                        new TextContent(
                                "{\"count\":2}",
                                Map.of(
                                        "a2a.data",
                                        StateValue.bool(true),
                                        "a2a.mediaType",
                                        StateValue.string("application/json")))),
                null,
                "framework-message",
                Map.of("trace", StateValue.string("fixture")));

        // Act
        Message a2a = A2AContentConverter.toA2AMessage(List.of(framework), null, List.of("*/*"), codec);
        com.microsoft.agents.core.Message decoded = A2AContentConverter.toFrameworkMessage(a2a, List.of("*/*"), codec);

        // Assert
        assertThat(a2a.parts())
                .hasExactlyElementsOfTypes(TextPart.class, FilePart.class, FilePart.class, DataPart.class);
        assertThat(decoded.messageId()).isEqualTo("framework-message");
        assertThat(decoded.contents()).hasSize(4);
        assertThat(((DataContent) decoded.contents().get(2)).mediaType()).isEqualTo("audio/wav");
        assertThat(decoded.metadata()).containsEntry("trace", StateValue.string("fixture"));
    }

    @Test
    void converter_shouldSendOnlyFinalUserMessageAndUseReferenceForCompletedTask() {
        // Arrange
        com.microsoft.agents.core.Message old =
                com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.USER, "old");
        com.microsoft.agents.core.Message assistant =
                com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.ASSISTANT, "answer");
        com.microsoft.agents.core.Message latest =
                com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.USER, "latest");
        A2AContinuation continuation = new A2AContinuation("task-1", "context-1", TaskState.TASK_STATE_COMPLETED);

        // Act
        Message converted = A2AContentConverter.toA2AMessage(
                List.of(old, assistant, latest), continuation, List.of("text/plain"), codec);

        // Assert
        assertThat(((TextPart) converted.parts().getFirst()).text()).isEqualTo("latest");
        assertThat(converted.taskId()).isNull();
        assertThat(converted.contextId()).isEqualTo("context-1");
        assertThat(converted.referenceTaskIds()).containsExactly("task-1");
    }

    @Test
    void unsupportedContentAndOutputMode_shouldFailActionably() {
        // Arrange
        com.microsoft.agents.core.Message unsupported = new com.microsoft.agents.core.Message(
                com.microsoft.agents.core.Role.USER,
                List.of(new FunctionCallContent("call", "tool", StateValue.object(Map.of()))));

        // Act / Assert
        assertThatThrownBy(() -> A2AContentConverter.toA2AMessage(List.of(unsupported), null, List.of("*/*"), codec))
                .isInstanceOf(A2AConversionException.class)
                .hasMessageContaining("text, URI/file bytes, and JSON data");
        assertThatThrownBy(() -> A2AContentConverter.toFrameworkMessage(
                        Message.builder(Role.ROLE_AGENT)
                                .parts(List.of(FilePart.bytes(new byte[] {1}, "image.png", "image/png", Map.of())))
                                .build(),
                        List.of("text/plain"),
                        codec))
                .isInstanceOf(A2AConversionException.class)
                .hasMessageContaining("image/png");
    }
}
