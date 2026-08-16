// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.StateValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AGUIOfficialInteropFixtureTest {
    private static final String ROOT = "agui/official/0.0.57/";

    private final AGUIJsonCodec codec = new AGUIJsonCodec(AGUILimits.defaults());

    @Test
    void manifest_shouldPinOfficialTypeScriptEncoderDotnetAndCommunityJavaEvidence() throws IOException {
        // Act
        StateValue.ObjectValue manifest = (StateValue.ObjectValue) codec.decodeValue(resource("manifest.json"));

        // Assert
        StateValue.ObjectValue typescript =
                (StateValue.ObjectValue) manifest.values().get("typescript");
        StateValue.ObjectValue core =
                (StateValue.ObjectValue) typescript.values().get("core");
        StateValue.ObjectValue encoder =
                (StateValue.ObjectValue) typescript.values().get("encoder");
        StateValue.ObjectValue client =
                (StateValue.ObjectValue) typescript.values().get("client");
        assertThat(((StateValue.StringValue) core.values().get("version")).value())
                .isEqualTo(AGUIProtocol.TYPESCRIPT_CORE_VERSION);
        assertThat(((StateValue.StringValue) encoder.values().get("version")).value())
                .isEqualTo(AGUIProtocol.TYPESCRIPT_ENCODER_VERSION);
        assertThat(((StateValue.StringValue) client.values().get("version")).value())
                .isEqualTo(AGUIProtocol.TYPESCRIPT_CLIENT_VERSION);
        assertThat(((StateValue.StringValue) core.values().get("integrity")).value())
                .startsWith("sha512-");
    }

    @Test
    void officialFrames_shouldDecodeValidateAndReencodeByteForByte() throws IOException {
        // Arrange
        byte[] ndjson = resource("events.ndjson");
        byte[] sse = resource("events.sse");

        // Act
        List<AGUIEvent> ndjsonEvents = codec.decodeNdjson(ndjson);
        AGUIEventStreamValidator validator = new AGUIEventStreamValidator(AGUILimits.defaults());
        ndjsonEvents.forEach(validator::accept);
        validator.finish();
        List<AGUIEvent> sseEvents = parseSse(sse);

        // Assert
        assertThat(sseEvents).containsExactlyElementsOf(ndjsonEvents);
        assertThat(codec.encodeNdjson(ndjsonEvents)).isEqualTo(ndjson);
        java.io.ByteArrayOutputStream encodedSse = new java.io.ByteArrayOutputStream();
        ndjsonEvents.forEach(event -> encodedSse.writeBytes(codec.encodeSseFrame(event)));
        assertThat(encodedSse.toByteArray()).isEqualTo(sse);
    }

    @Test
    void officialRunAgentInput_shouldDecodeAndRemainCanonical() throws IOException {
        // Arrange
        byte[] fixture = resource("run-agent-input.json");

        // Act
        RunAgentInput input = codec.decodeRunAgentInput(fixture);

        // Assert
        assertThat(input.threadId()).isEqualTo("thread-1");
        assertThat(input.messages()).singleElement().isInstanceOf(AGUIMessages.User.class);
        assertThat(new String(codec.encodeRunAgentInput(input), StandardCharsets.UTF_8))
                .isEqualTo(new String(fixture, StandardCharsets.UTF_8).stripTrailing());
    }

    private List<AGUIEvent> parseSse(byte[] bytes) {
        AGUISseParser parser = new AGUISseParser(codec);
        ArrayList<AGUIEvent> result = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\n", -1)) {
            result.addAll(parser.acceptLine(line));
        }
        result.addAll(parser.finish());
        return List.copyOf(result);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream stream =
                AGUIOfficialInteropFixtureTest.class.getClassLoader().getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IOException("Missing fixture " + name);
            }
            return stream.readAllBytes();
        }
    }
}
