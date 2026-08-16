// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateEnvelope;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageStateCodecTest {
    private final MessageStateCodec codec = new MessageStateCodec();

    @Test
    void codec_shouldRoundTripDetachedMessagesWithoutPolymorphicTypeNames() {
        // Arrange
        Message source = new Message(
                Role.ASSISTANT,
                List.of(new TextContent("hello", Map.of("content.flag", StateValue.bool(true)))),
                "assistant-a",
                "message-a",
                Map.of("message.flag", StateValue.string("value")));

        // Act
        StateValue.ObjectValue encoded = codec.encode(source);
        Message decoded = codec.decode(encoded);

        // Assert
        assertThat(decoded).isEqualTo(source).isNotSameAs(source);
        assertThat(encoded.toString())
                .doesNotContain(source.getClass().getName())
                .doesNotContain(TextContent.class.getName());
    }

    @Test
    void codec_shouldRejectMalformedAndUnknownContentDiscriminators() {
        StateValue malformed = StateValue.object(Map.of(
                "role",
                StateValue.string("user"),
                "contents",
                StateValue.array(List.of(StateValue.object(Map.of("kind", StateValue.string("future")))))));

        assertThatThrownBy(() -> codec.decode(malformed))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Unknown content discriminator");
    }

    @Test
    void serializerDefaults_shouldSupportStrictHistoryMessageEnvelopeVersionOne() {
        // Arrange
        JsonStateSerializer serializer = new JsonStateSerializer(new SerializationLimits(4096, 32, 2048, 128, 256));
        StateEnvelope envelope =
                StateEnvelope.of(DocumentKind.HISTORY_MESSAGE, 1, codec.encode(Message.text(Role.USER, "hello")));

        // Act
        byte[] encoded = serializer.write(envelope);
        StateEnvelope decoded = serializer.read(encoded, DocumentKind.HISTORY_MESSAGE);

        // Assert
        assertThat(decoded).isEqualTo(envelope);
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .startsWith("{\"documentKind\":\"history-message\",\"format\":\"agent-framework-java-state\"");
    }

    @Test
    void explicitSerializerVersions_shouldAllowOmittedNewKindsAndRejectThemOnUse() {
        // Arrange
        JsonStateSerializer serializer = new JsonStateSerializer(
                new SerializationLimits(4096, 32, 2048, 128, 256),
                Map.of(DocumentKind.AGENT_SESSION, Set.of(1), DocumentKind.WORKFLOW_CHECKPOINT, Set.of(1)));
        StateEnvelope history =
                StateEnvelope.of(DocumentKind.HISTORY_MESSAGE, 1, codec.encode(Message.text(Role.USER, "hello")));

        // Act and assert
        assertThatThrownBy(() -> serializer.write(history))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Unsupported history-message payload version");
    }
}
