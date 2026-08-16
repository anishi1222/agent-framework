// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class A2AJsonCodecTest {
    private final A2AJsonCodec codec = new A2AJsonCodec(A2ALimits.defaults());

    @Test
    void agentCardRoundTrip_shouldPreserveV1FieldsAndUnknownAdditions() {
        // Arrange
        AgentCard card = AgentCard.builder("weather", "Weather agent", "2.0.0")
                .provider(new AgentProvider("Microsoft", URI.create("https://microsoft.com")))
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(true)
                        .extendedAgentCard(true)
                        .extensions(List.of(new AgentExtension(
                                URI.create("https://example.test/ext"),
                                "extension",
                                false,
                                Map.of("mode", StateValue.string("safe")))))
                        .build())
                .skills(List.of(AgentSkill.builder("forecast", "Forecast", "Gets forecasts")
                        .tags(List.of("weather"))
                        .examples(List.of("Tomorrow?"))
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("application/json"))
                        .build()))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("https://agent.test/a2a"))))
                .securitySchemes(Map.of("bearer", new SecurityScheme.Http("bearer", "JWT", "token")))
                .securityRequirements(List.of(SecurityRequirement.of("bearer", List.of("read"))))
                .additionalProperties(
                        Map.of("x-extension", StateValue.object(Map.of("enabled", StateValue.bool(true)))))
                .build();

        // Act
        AgentCard decoded = codec.agentCardFromValue(codec.parse(codec.write(codec.agentCardToValue(card))));

        // Assert
        assertThat(decoded).isEqualTo(card);
        assertThat(codec.writeString(codec.agentCardToValue(decoded)))
                .isEqualTo(codec.writeString(codec.agentCardToValue(card)));
    }

    @Test
    void agentSkillWireShape_shouldUseExactV1KeysAndKeepMetadataLocalOnly() {
        // Arrange
        AgentSkill skill = AgentSkill.builder("forecast", "Forecast", "Gets forecasts")
                .tags(List.of("weather"))
                .examples(List.of("Tomorrow?"))
                .inputModes(List.of("text/plain"))
                .outputModes(List.of("application/json"))
                .securityRequirements(List.of(SecurityRequirement.of("bearer", List.of("read"))))
                .metadata(Map.of("local-secret", StateValue.string("not-on-wire")))
                .build();
        AgentCard card = AgentCard.builder("weather", "Weather agent", "2.0.0")
                .skills(List.of(skill))
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("https://agent.test/a2a"))))
                .build();

        // Act
        StateValue.ObjectValue encoded = codec.agentCardToValue(card);
        StateValue.ArrayValue skills = (StateValue.ArrayValue) encoded.values().get("skills");
        StateValue.ObjectValue encodedSkill =
                (StateValue.ObjectValue) skills.values().getFirst();
        AgentCard decoded = codec.agentCardFromValue(encoded);

        // Assert
        assertThat(encodedSkill.values())
                .containsOnlyKeys(
                        "id",
                        "name",
                        "description",
                        "tags",
                        "examples",
                        "inputModes",
                        "outputModes",
                        "securityRequirements")
                .doesNotContainKey("metadata");
        assertThat(decoded.skills().getFirst().metadata()).isEmpty();
        assertThat(skill.metadata()).containsEntry("local-secret", StateValue.string("not-on-wire"));
    }

    @Test
    void messageRoundTrip_shouldPreserveTextFileDataMetadataAndCorrelation() {
        // Arrange
        Message message = Message.builder(Role.ROLE_USER)
                .messageId("message-1")
                .contextId("context-1")
                .taskId("task-1")
                .parts(List.of(
                        new TextPart("hello"),
                        FilePart.bytes(
                                new byte[] {1, 2, 3},
                                "image.png",
                                "image/png",
                                Map.of("source", StateValue.string("camera"))),
                        new DataPart(StateValue.object(Map.of("answer", StateValue.integer(42))))))
                .metadata(Map.of("trace", StateValue.string("abc")))
                .extensions(List.of(URI.create("https://example.test/message-ext")))
                .build();

        // Act
        Message decoded = codec.messageFromValue(codec.parse(codec.write(codec.messageToValue(message))));

        // Assert
        assertThat(decoded).isEqualTo(message);
    }

    @Test
    void taskAndStreamRoundTrip_shouldPreserveArtifactChunkFlags() {
        // Arrange
        Instant timestamp = Instant.parse("2026-08-08T00:00:00Z");
        Artifact artifact = Artifact.builder("artifact-1")
                .parts(List.of(new TextPart("chunk")))
                .build();
        Task task = Task.builder("task-1", "context-1", new TaskStatus(TaskState.TASK_STATE_WORKING, timestamp))
                .artifacts(List.of(artifact))
                .build();
        TaskArtifactUpdateEvent event =
                new TaskArtifactUpdateEvent(task.id(), task.contextId(), artifact, true, true, Map.of());

        // Act
        Task decodedTask = codec.taskFromValue(codec.taskToValue(task));
        A2AStreamEvent decodedEvent = codec.streamEventFromValue(codec.streamEventToValue(event));

        // Assert
        assertThat(decodedTask).isEqualTo(task);
        assertThat(decodedEvent).isEqualTo(event);
    }

    @Test
    void canonicalWriter_shouldOrderEveryObjectKeyRecursively() {
        // Arrange
        StateValue value = StateValue.object(Map.of(
                "z", StateValue.object(Map.of("b", StateValue.integer(2), "a", StateValue.integer(1))),
                "a", StateValue.string("first")));

        // Act
        String json = codec.writeString(value);

        // Assert
        assertThat(json).isEqualTo("{\"a\":\"first\",\"z\":{\"a\":1,\"b\":2}}");
    }

    @Test
    void parser_shouldRejectDuplicateKeysAndTrailingContent() {
        // Act / Assert
        assertThatThrownBy(() -> codec.parse("{\"a\":1,\"a\":2}"))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("duplicate key");
        assertThatThrownBy(() -> codec.parse("{}{}"))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    void parser_shouldEnforceDepthStringAndCollectionBounds() {
        // Arrange
        A2AJsonCodec bounded = new A2AJsonCodec(new A2ALimits(100, 100, 3, 4, 2, 100, 1, 1));

        // Act / Assert
        assertThatThrownBy(() -> bounded.parse("{\"a\":{\"b\":{\"c\":{\"d\":1}}}}"))
                .isInstanceOf(A2ATransportException.class);
        assertThatThrownBy(() -> bounded.parse("{\"a\":\"12345\"}")).isInstanceOf(A2ATransportException.class);
        assertThatThrownBy(() -> bounded.parse("[1,2,3]")).isInstanceOf(A2ATransportException.class);
    }

    @Test
    void continuationRoundTrip_shouldRetainCompletedContextForRefinement() {
        // Arrange
        A2AContinuation continuation = new A2AContinuation("task-1", "context-1", TaskState.TASK_STATE_COMPLETED);

        // Act
        A2AContinuation decoded = A2AContinuation.fromStateValue(continuation.toStateValue());

        // Assert
        assertThat(decoded).isEqualTo(continuation);
    }

    @Test
    void invalidOneOfAndUnknownEnums_shouldFailAsProtocolErrors() {
        // Act / Assert
        assertThatThrownBy(() -> codec.streamEventFromValue(StateValue.object(Map.of(
                        "task", StateValue.object(Map.of()),
                        "message", StateValue.object(Map.of())))))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> codec.taskStatusFromValue(StateValue.object(Map.of(
                        "state",
                        StateValue.string("COMPLETED"),
                        "timestamp",
                        StateValue.string("2026-08-08T00:00:00Z")))))
                .isInstanceOf(A2AProtocolException.class)
                .hasMessageContaining("Unknown state");
    }

    @Test
    void v1ProtoJsonGolden_shouldUseFlattenedPartsStringListsAndOAuthOneOf() {
        // Arrange
        Message fileMessage = Message.builder(Role.ROLE_USER)
                .messageId("message")
                .parts(List.of(FilePart.bytes(new byte[] {1, 2}, "image.png", "image/png", Map.of())))
                .build();
        SecurityScheme.OAuth2 oauth = new SecurityScheme.OAuth2(
                new SecurityScheme.AuthorizationCode(
                        URI.create("https://identity.test/authorize"),
                        URI.create("https://identity.test/token"),
                        null,
                        Map.of("read", "Read access"),
                        true),
                URI.create("https://identity.test/.well-known/oauth-authorization-server"),
                "OAuth");
        AgentCard card = AgentCard.builder("secure", "secure", "1.0")
                .skills(List.of())
                .supportedInterfaces(List.of(AgentInterface.jsonRpc(URI.create("https://agent.test/a2a"))))
                .securitySchemes(Map.of("oauth", oauth))
                .securityRequirements(List.of(SecurityRequirement.of("oauth", List.of("read"))))
                .build();

        // Act
        String messageJson = codec.writeString(codec.messageToValue(fileMessage));
        String cardJson = codec.writeString(codec.agentCardToValue(card));

        // Assert
        assertThat(messageJson)
                .contains("\"raw\":\"AQI=\"")
                .contains("\"filename\":\"image.png\"")
                .contains("\"mediaType\":\"image/png\"")
                .doesNotContain("\"file\"");
        assertThat(cardJson)
                .contains("\"schemes\":{\"oauth\":{\"list\":[\"read\"]}}")
                .contains("\"authorizationCode\"")
                .contains("\"pkceRequired\":true")
                .doesNotContain("\"clientCredentials\"");
    }

    @Test
    void optionalTaskContextAndTimestamp_shouldDecodeWithoutFabrication() {
        // Arrange
        StateValue value = StateValue.object(Map.of(
                "id",
                StateValue.string("task"),
                "status",
                StateValue.object(Map.of("state", StateValue.string("TASK_STATE_WORKING")))));

        // Act
        Task task = codec.taskFromValue(value);

        // Assert
        assertThat(task.contextId()).isNull();
        assertThat(task.status().timestamp()).isNull();
    }
}
