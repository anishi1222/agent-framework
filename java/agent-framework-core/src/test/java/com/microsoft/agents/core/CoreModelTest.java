// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreModelTest {
    @Test
    void message_shouldDefensivelyCopyCollections_andProvideValueEquality() {
        // Arrange
        ArrayList<Content> contents = new ArrayList<>(List.of(new TextContent("hello")));
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(Map.of("tenant", StateValue.string("one")));

        // Act
        Message first = new Message(Role.USER, contents, "author", "message-1", metadata);
        Message equal = new Message(
                Role.of("user"),
                List.of(new TextContent("hello")),
                "author",
                "message-1",
                Map.of("tenant", StateValue.string("one")));
        contents.add(new TextContent("mutated"));
        metadata.put("later", StateValue.bool(true));

        // Assert
        assertThat(first).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(first.text()).isEqualTo("hello");
        assertThat(first.contents()).hasSize(1);
        assertThat(first.metadata()).containsOnlyKeys("tenant");
        assertThatThrownBy(() -> first.contents().add(new TextContent("x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.metadata().put("x", StateValue.nullValue()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void messageText_shouldJoinTextItemsAcrossNonTextContentWithOneSpace() {
        // Arrange
        Message message = new Message(
                Role.USER,
                List.of(
                        new TextContent("inspect"),
                        new DataContent(new byte[] {1}, "application/octet-stream"),
                        new TextContent("now")));

        // Act
        String text = message.text();

        // Assert
        assertThat(text).isEqualTo("inspect now");
    }

    @Test
    void dataContent_shouldDefensivelyCopyBytes_andUseContentEquality() {
        // Arrange
        byte[] bytes = {1, 2, 3};

        // Act
        DataContent content = new DataContent(bytes, "application/octet-stream");
        bytes[0] = 9;
        byte[] returned = content.data();
        returned[1] = 8;

        // Assert
        assertThat(content.data()).containsExactly(1, 2, 3);
        assertThat(content)
                .isEqualTo(new DataContent(new byte[] {1, 2, 3}, "application/octet-stream"))
                .hasSameHashCodeAs(new DataContent(new byte[] {1, 2, 3}, "application/octet-stream"));
        assertThat(DataContent.fromDataUri(content.dataUri().toString())).isEqualTo(content);
    }

    @Test
    void roleAndFinishReason_shouldNormalizeKnownValues_andPreserveCustomValues() {
        assertThat(Role.of("assistant")).isSameAs(Role.ASSISTANT);
        assertThat(Role.of("critic").value()).isEqualTo("critic");
        assertThat(FinishReason.of("tool_calls")).isSameAs(FinishReason.TOOL_CALLS);
        assertThat(FinishReason.of("content-filter")).isSameAs(FinishReason.CONTENT_FILTER);
        assertThat(FinishReason.of("providerReason").value()).isEqualTo("providerReason");
    }

    @Test
    void options_shouldValidateRanges_andDefensivelyCopyValues() {
        // Arrange
        ArrayList<String> stop = new ArrayList<>(List.of("END"));
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(Map.of("tenant", StateValue.string("one")));

        // Act
        ChatOptions options = new ChatOptions(
                "model",
                0.25,
                0.9,
                128,
                stop,
                7L,
                -0.5,
                0.5,
                ToolChoice.AUTO,
                true,
                "user",
                false,
                "conversation",
                "answer",
                metadata);
        RunOptions runOptions = RunOptions.builder()
                .maxIterations(4)
                .maxFunctionCalls(3)
                .metadata(metadata)
                .build();
        stop.add("LATER");
        metadata.put("later", StateValue.bool(true));

        // Assert
        assertThat(options.stop()).containsExactly("END");
        assertThat(options.metadata()).containsOnlyKeys("tenant");
        assertThat(runOptions.metadata()).containsOnlyKeys("tenant");
        assertThat(options.toolChoice().value()).isEqualTo("auto");
    }

    @Test
    void publicValues_shouldRejectNullBlankAndInvalidInputs() {
        assertThatThrownBy(() -> Role.of(" ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> StateValue.object(Map.of("", StateValue.nullValue())))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> StateValue.object(Map.of(" \t", StateValue.nullValue())))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Message.text(null, "text")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Message(Role.USER, List.of((Content) null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReasoningContent(null, null, null, Map.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new UriContent(URI.create("data:text/plain,hello"), "text/plain"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ChatOptions.builder().temperature(Double.NaN).build())
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ChatOptions.builder().maxTokens(0).build()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RunOptions.builder().maxIterations(0).build()).isInstanceOf(ValidationException.class);
    }
}
