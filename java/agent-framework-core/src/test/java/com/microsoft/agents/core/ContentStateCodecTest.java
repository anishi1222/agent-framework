// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContentStateCodecTest {
    private final ContentStateCodec codec = new ContentStateCodec();

    static Stream<Content> contentValues() {
        Map<String, StateValue> metadata = Map.of("source", StateValue.string("test"));
        return Stream.of(
                new TextContent("hello", metadata),
                new ReasoningContent("reason-1", "think", "protected", metadata),
                new DataContent(new byte[] {1, 2, 3}, "image/png", metadata),
                new UriContent(URI.create("https://example.test/image.png"), "image/png", metadata),
                new ErrorContent("failed", "E1", "details", metadata),
                new FunctionCallContent(
                        "call-1",
                        "inspect",
                        StateValue.object(Map.of("value", StateValue.integer(7))),
                        false,
                        metadata),
                new FunctionResultContent(
                        "call-1",
                        StateValue.object(Map.of("accepted", StateValue.bool(true))),
                        List.of(new TextContent("done")),
                        null,
                        metadata),
                new UsageContent(UsageDetails.of(UsageDetails.INPUT_TOKENS, 3), metadata),
                new MetadataContent(Map.of("trace", StateValue.string("trace-1"))));
    }

    @ParameterizedTest
    @MethodSource("contentValues")
    void codec_shouldRoundTripEveryInitialContentVariant(Content content) {
        assertThat(codec.decode(codec.encode(content), codec.currentVersion())).isEqualTo(content);
    }

    @Test
    void codec_shouldRejectUnknownDiscriminator_withoutInterpretingClassNames() {
        StateValue value = StateValue.object(Map.of(
                "kind", StateValue.string("com.example.Credential"),
                "@class", StateValue.string("java.lang.Runtime")));

        assertThatThrownBy(() -> codec.decode(value, 1))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.MALFORMED_DOCUMENT);
    }

    @Test
    void codec_shouldIgnoreUnknownAdditiveProperties() {
        StateValue value = StateValue.object(Map.of(
                "kind", StateValue.string("text"),
                "text", StateValue.string("hello"),
                "futureProperty", StateValue.object(Map.of("enabled", StateValue.bool(true)))));

        assertThat(codec.decode(value, 1)).isEqualTo(new TextContent("hello"));
    }

    @Test
    void encodedContent_shouldUseStableDiscriminator_withoutJavaTypeMetadata() {
        StateCodecRegistry registry = new StateCodecRegistry();
        registry.register(codec);
        EncodedState encoded = registry.encode(codec, new TextContent("hello"));
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        String json = new String(
                serializer.write(StateEnvelope.of(DocumentKind.AGENT_SESSION, 1, encoded.toStateValue())),
                StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"typeId\":\"com.microsoft.agents.core.content\"")
                .contains("\"kind\":\"text\"")
                .doesNotContain("@class", "java.lang", "TextContent");
    }
}
