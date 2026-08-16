// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.conformance.SerializationDocumentKind;
import com.microsoft.agents.conformance.SerializationPositiveControl;
import com.microsoft.agents.conformance.SerializationReadResult;
import com.microsoft.agents.conformance.SerializationRejectionCase;
import com.microsoft.agents.conformance.SerializationRejectionCorpus;
import com.microsoft.agents.conformance.SerializationRejectionCorpusLoader;
import com.microsoft.agents.conformance.SerializationRejectionReason;
import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSessionCodecTest {
    @Test
    void encodeDecode_shouldRoundTripVersionOneAndIgnoreAdditiveProperties() {
        // Arrange
        AgentSessionCodec codec = codec(SerializationLimits.defaults());
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
                "session-001",
                List.of(Message.text(Role.USER, "hello")),
                new AgentSessionStateBag(Map.of("turn", StateValue.integer(1))));

        // Act
        byte[] encoded = codec.encode(snapshot);
        String withAdditive = new String(encoded, StandardCharsets.UTF_8)
                .replaceFirst("\\{\"documentKind\"", "{\"additive\":{\"ignored\":true},\"documentKind\"");
        AgentSessionSnapshot decoded = codec.decode(withAdditive.getBytes(StandardCharsets.UTF_8));

        // Assert
        assertThat(decoded).isEqualTo(snapshot);
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .doesNotContain("ChatClient", "Executor", "credential", "@class")
                .contains("\"documentKind\":\"agent-session\"")
                .contains("\"payloadVersion\":1");
    }

    @Test
    void decode_shouldRejectMissingRequiredStateAndUnknownContentDiscriminator() {
        // Arrange
        AgentSessionCodec codec = codec(SerializationLimits.defaults());
        String missingState = """
                {"format":"agent-framework-java-state","documentKind":"agent-session","payloadVersion":1,
                "payload":{"sessionId":"session-1"}}
                """;
        String unknownContent = """
                {"format":"agent-framework-java-state","documentKind":"agent-session","payloadVersion":1,
                "payload":{"sessionId":"session-1","state":{},"messages":[{"role":"user",
                "contents":[{"kind":"java.lang.Runtime","value":"unsafe"}]}]}}
                """;

        // Act and assert
        assertThatThrownBy(() -> codec.decode(missingState.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .extracting("error")
                .isEqualTo(SerializationError.MALFORMED_DOCUMENT);
        assertThatThrownBy(() -> codec.decode(unknownContent.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("Unknown content discriminator");
    }

    @Test
    void productionReader_shouldBindSessionPositiveControlAndAllPortableRejections() throws Exception {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Act and assert
        for (SerializationPositiveControl control : corpus.positiveControls()) {
            if (control.documentKind() == SerializationDocumentKind.AGENT_SESSION) {
                assertThat(read(control.documentKind(), corpus.readRaw(control), control.limits()))
                        .isInstanceOf(SerializationReadResult.Accepted.class);
            }
        }
        for (SerializationRejectionCase rejection : corpus.cases()) {
            if (rejection.documentKind() == SerializationDocumentKind.AGENT_SESSION) {
                assertThat(read(rejection.documentKind(), corpus.readRaw(rejection), rejection.limits()))
                        .isEqualTo(SerializationReadResult.rejected(rejection.reason()));
            }
        }
    }

    private static SerializationReadResult read(
            SerializationDocumentKind kind, byte[] raw, com.microsoft.agents.conformance.SerializationLimits limits) {
        if (kind != SerializationDocumentKind.AGENT_SESSION) {
            throw new AssertionError("Unexpected reader kind " + kind);
        }
        try {
            codec(new SerializationLimits(
                            limits.maxDocumentBytes(),
                            limits.maxNestingDepth(),
                            limits.maxStringLength(),
                            limits.maxNumericTokenLength(),
                            limits.maxCollectionEntries()))
                    .decode(raw);
            return SerializationReadResult.accepted();
        } catch (SerializationException exception) {
            return SerializationReadResult.rejected(map(exception.error()));
        }
    }

    private static SerializationRejectionReason map(SerializationError error) {
        return switch (error) {
            case DUPLICATE_KEY -> SerializationRejectionReason.DUPLICATE_KEY;
            case DOCUMENT_BYTES -> SerializationRejectionReason.DOCUMENT_BYTES;
            case NESTING_DEPTH -> SerializationRejectionReason.NESTING_DEPTH;
            case STRING_LENGTH -> SerializationRejectionReason.STRING_LENGTH;
            case NUMERIC_TOKEN_LENGTH -> SerializationRejectionReason.NUMERIC_TOKEN_LENGTH;
            case COLLECTION_ENTRIES -> SerializationRejectionReason.COLLECTION_ENTRIES;
            case NON_FINITE_NUMBER -> SerializationRejectionReason.NON_FINITE_NUMBER;
            case WRONG_DOCUMENT_KIND -> SerializationRejectionReason.WRONG_DOCUMENT_KIND;
            case UNSUPPORTED_PAYLOAD_VERSION -> SerializationRejectionReason.UNSUPPORTED_PAYLOAD_VERSION;
            default -> throw exception(error);
        };
    }

    private static AssertionError exception(SerializationError error) {
        return new AssertionError("Unexpected serialization error " + error);
    }

    private static AgentSessionCodec codec(SerializationLimits limits) {
        return new AgentSessionCodec(new JsonStateSerializer(
                limits,
                Map.of(
                        DocumentKind.AGENT_SESSION,
                        java.util.Set.of(1),
                        DocumentKind.HISTORY_MESSAGE,
                        java.util.Set.of(1),
                        DocumentKind.WORKFLOW_CHECKPOINT,
                        java.util.Set.of(1))));
    }
}
