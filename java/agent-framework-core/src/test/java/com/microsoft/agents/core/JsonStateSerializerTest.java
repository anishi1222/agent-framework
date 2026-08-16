// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.json.PackageVersion;
import com.microsoft.agents.conformance.SerializationDocumentKind;
import com.microsoft.agents.conformance.SerializationPositiveControl;
import com.microsoft.agents.conformance.SerializationReadResult;
import com.microsoft.agents.conformance.SerializationReaderAdapter;
import com.microsoft.agents.conformance.SerializationRejectionAssertions;
import com.microsoft.agents.conformance.SerializationRejectionCase;
import com.microsoft.agents.conformance.SerializationRejectionCorpus;
import com.microsoft.agents.conformance.SerializationRejectionCorpusLoader;
import com.microsoft.agents.conformance.SerializationRejectionReason;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonStateSerializerTest {
    private static final SerializationRejectionCorpus CORPUS = new SerializationRejectionCorpusLoader().loadDefault();

    @Test
    void serializer_shouldProduceDeterministicEnvelopeAndSortedObjectKeys() {
        // Arrange
        LinkedHashMap<String, StateValue> payload = new LinkedHashMap<>();
        payload.put("z", StateValue.integer(2));
        payload.put("a", StateValue.integer(1));
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        // Act
        byte[] first = serializer.write(StateEnvelope.of(DocumentKind.AGENT_SESSION, 1, StateValue.object(payload)));
        byte[] second = serializer.write(StateEnvelope.of(DocumentKind.AGENT_SESSION, 1, StateValue.object(payload)));

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8))
                .isEqualTo("{\"documentKind\":\"agent-session\",\"format\":\"agent-framework-java-state\","
                        + "\"payload\":{\"a\":1,\"z\":2},\"payloadVersion\":1}");
    }

    @Test
    void serializer_shouldSortNestedObjectKeysAndPreserveArrayOrder() {
        // Arrange
        StateValue payload = StateValue.object(Map.of(
                "items",
                StateValue.array(List.of(
                        StateValue.object(Map.of("z", StateValue.integer(1), "a", StateValue.integer(2))),
                        StateValue.object(Map.of("b", StateValue.integer(3), "a", StateValue.integer(4)))))));
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        // Act
        String encoded = new String(
                serializer.write(StateEnvelope.of(DocumentKind.AGENT_SESSION, 1, payload)), StandardCharsets.UTF_8);

        // Assert
        assertThat(encoded)
                .isEqualTo("{\"documentKind\":\"agent-session\",\"format\":\"agent-framework-java-state\","
                        + "\"payload\":{\"items\":[{\"a\":2,\"z\":1},{\"a\":4,\"b\":3}]},"
                        + "\"payloadVersion\":1}");
    }

    @Test
    void constraintMapping_shouldRemainPinnedToTheResolvedJacksonVersion() {
        assertThat(PackageVersion.VERSION.toString()).isEqualTo(JacksonStreamConstraintMapper.MAPPED_JACKSON_VERSION);
    }

    @Test
    void reader_shouldIgnoreUnknownAdditiveEnvelopeProperties() {
        // Arrange
        String json = """
                {
                  "format": "agent-framework-java-state",
                  "documentKind": "agent-session",
                  "payloadVersion": 1,
                  "futureEnvelope": {"enabled": true},
                  "payload": {"sessionId": "session-1", "futurePayload": 7}
                }
                """;
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        // Act
        StateEnvelope envelope = serializer.read(json.getBytes(StandardCharsets.UTF_8), DocumentKind.AGENT_SESSION);

        // Assert
        assertThat(envelope.payload()).isInstanceOf(StateValue.ObjectValue.class);
        assertThat(((StateValue.ObjectValue) envelope.payload()).values()).containsKeys("sessionId", "futurePayload");
    }

    @Test
    void reader_shouldRejectTrailingContent() {
        String json = "{\"format\":\"agent-framework-java-state\",\"documentKind\":\"agent-session\","
                + "\"payloadVersion\":1,\"payload\":{}} {}";
        JsonStateSerializer serializer = new JsonStateSerializer(SerializationLimits.defaults());

        assertThatThrownBy(() -> serializer.read(json.getBytes(StandardCharsets.UTF_8), DocumentKind.AGENT_SESSION))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.TRAILING_CONTENT);
    }

    @Test
    void writer_shouldEnforceLimitsBeforeReturningBytes() {
        JsonStateSerializer serializer = new JsonStateSerializer(new SerializationLimits(128, 4, 8, 4, 8));
        StateEnvelope envelope = StateEnvelope.of(
                DocumentKind.AGENT_SESSION,
                1,
                StateValue.object(Map.of("long-key", StateValue.string("too-long-value"))));

        assertThatThrownBy(() -> serializer.write(envelope))
                .isInstanceOf(SerializationException.class)
                .extracting(exception -> ((SerializationException) exception).error())
                .isEqualTo(SerializationError.STRING_LENGTH);
    }

    static Stream<Arguments> positiveControls() {
        return CORPUS.positiveControls().stream().map(control -> Arguments.of(control.controlId(), control));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positiveControls")
    void rawPositiveControl_shouldBeAcceptedByProductionReader(String controlId, SerializationPositiveControl control)
            throws Exception {
        assertThat(controlId).startsWith("JCF-POSITIVE-");
        SerializationRejectionAssertions.assertAccepted(CORPUS, control.controlId(), productionReader());
    }

    static Stream<Arguments> rejectionCases() {
        return CORPUS.cases().stream().map(rejectionCase -> Arguments.of(rejectionCase.caseId(), rejectionCase));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectionCases")
    void rawRejectionCase_shouldReportExpectedPortableReason(String caseId, SerializationRejectionCase rejectionCase)
            throws Exception {
        assertThat(caseId).startsWith("JCF-REJECTIONS-");
        byte[] raw = CORPUS.readRaw(rejectionCase);
        JsonStateSerializer serializer = serializerFor(rejectionCase);
        try {
            serializer.read(raw, toDocumentKind(rejectionCase.documentKind()));
            throw new AssertionError("Serialization rejection case "
                    + caseId
                    + " expected category <"
                    + rejectionCase.reason().wireName()
                    + "> but production accepted it.");
        } catch (SerializationException exception) {
            SerializationRejectionReason actual = portableReason(exception.error());
            assertThat(actual)
                    .withFailMessage(
                            "Serialization rejection case %s expected category <%s> but production reported <%s>.",
                            caseId, rejectionCase.reason().wireName(), exception.error())
                    .isEqualTo(rejectionCase.reason());
        }
    }

    private static JsonStateSerializer serializerFor(SerializationRejectionCase rejectionCase) {
        com.microsoft.agents.conformance.SerializationLimits conformanceLimits = rejectionCase.limits();
        return new JsonStateSerializer(new SerializationLimits(
                conformanceLimits.maxDocumentBytes(),
                conformanceLimits.maxNestingDepth(),
                conformanceLimits.maxStringLength(),
                conformanceLimits.maxNumericTokenLength(),
                conformanceLimits.maxCollectionEntries()));
    }

    private static SerializationReaderAdapter productionReader() {
        return (kind, utf8Json, conformanceLimits) -> {
            JsonStateSerializer serializer = new JsonStateSerializer(new SerializationLimits(
                    conformanceLimits.maxDocumentBytes(),
                    conformanceLimits.maxNestingDepth(),
                    conformanceLimits.maxStringLength(),
                    conformanceLimits.maxNumericTokenLength(),
                    conformanceLimits.maxCollectionEntries()));
            try {
                serializer.read(utf8Json, toDocumentKind(kind));
                return SerializationReadResult.accepted();
            } catch (SerializationException exception) {
                return SerializationReadResult.rejected(toRejectionReason(exception.error()));
            }
        };
    }

    private static DocumentKind toDocumentKind(SerializationDocumentKind kind) {
        return switch (kind) {
            case AGENT_SESSION -> DocumentKind.AGENT_SESSION;
            case WORKFLOW_CHECKPOINT -> DocumentKind.WORKFLOW_CHECKPOINT;
        };
    }

    private static SerializationRejectionReason toRejectionReason(SerializationError error) {
        SerializationRejectionReason reason = portableReason(error);
        if (reason == null) {
            throw new AssertionError("Unexpected corpus rejection category " + error);
        }
        return reason;
    }

    private static SerializationRejectionReason portableReason(SerializationError error) {
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
            case TRAILING_CONTENT, MALFORMED_DOCUMENT, UNKNOWN_TYPE_ID, DUPLICATE_CODEC, CODEC_MIGRATION -> null;
        };
    }
}
