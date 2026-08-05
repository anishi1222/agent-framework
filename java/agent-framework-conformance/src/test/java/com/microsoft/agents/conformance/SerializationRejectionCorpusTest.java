// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SerializationRejectionCorpusTest {
    @Test
    void loadDefault_shouldExposeNineRawRejectionsPerStateReader() {
        // Act
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Assert
        assertThat(corpus.cases()).hasSize(18);
        assertThat(corpus.cases())
                .filteredOn(rejectionCase -> rejectionCase.documentKind() == SerializationDocumentKind.AGENT_SESSION)
                .hasSize(9);
        assertThat(corpus.cases())
                .filteredOn(
                        rejectionCase -> rejectionCase.documentKind() == SerializationDocumentKind.WORKFLOW_CHECKPOINT)
                .hasSize(9);
        assertThat(corpus.positiveControls())
                .extracting(SerializationPositiveControl::documentKind)
                .containsExactlyInAnyOrder(SerializationDocumentKind.values());
        for (SerializationDocumentKind kind : SerializationDocumentKind.values()) {
            Set<SerializationRejectionReason> reasons = corpus.cases().stream()
                    .filter(rejectionCase -> rejectionCase.documentKind() == kind)
                    .map(SerializationRejectionCase::reason)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(SerializationRejectionReason.class)));
            assertThat(reasons).containsExactlyInAnyOrder(SerializationRejectionReason.values());
        }
    }

    @Test
    void rawResources_shouldMatchRejectionManifestExactly() throws IOException {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();
        Path resourceRoot = Path.of(System.getProperty("conformance.fixture.source.dir"));
        Path rejectionRoot = resourceRoot.resolve("conformance/rejections/v1");
        Set<String> registered = Stream.concat(
                        corpus.positiveControls().stream().map(SerializationPositiveControl::resource),
                        corpus.cases().stream().map(SerializationRejectionCase::resource))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Act
        Set<String> files;
        try (var paths = Files.walk(rejectionRoot)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(resourceRoot::relativize)
                    .map(path -> path.toString().replace(path.getFileSystem().getSeparator(), "/"))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // Assert
        assertThat(files).containsExactlyInAnyOrderElementsOf(registered);
    }

    @Test
    void contractRunner_shouldAcceptControlsAndRejectEveryRawCaseForDeclaredReason() throws Exception {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Act and assert
        SerializationRejectionAssertions.assertConforms(
                corpus, SerializationRejectionCorpusTest::readWithPortableLimits);
    }

    @Test
    void contractRunner_shouldFailWhenReaderAcceptsUnsafeInput() {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Act and assert
        assertThatThrownBy(() -> SerializationRejectionAssertions.assertRejected(
                        corpus,
                        "JCF-REJECTIONS-SESSIONS-001",
                        (kind, raw, limits) -> SerializationReadResult.accepted()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JCF-REJECTIONS-SESSIONS-001")
                .hasMessageContaining("duplicate-key");
    }

    @Test
    void contractRunner_shouldFailWhenReaderReportsWrongRejectionReason() {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Act and assert
        assertThatThrownBy(() -> SerializationRejectionAssertions.assertRejected(
                        corpus,
                        "JCF-REJECTIONS-SESSIONS-001",
                        (kind, raw, limits) ->
                                SerializationReadResult.rejected(SerializationRejectionReason.DOCUMENT_BYTES)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected reason duplicate-key")
                .hasMessageContaining("reported document-bytes");
    }

    @Test
    void contractRunner_shouldFailWhenReaderRejectsEveryDocument() {
        // Arrange
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader().loadDefault();

        // Act and assert
        assertThatThrownBy(() -> SerializationRejectionAssertions.assertConforms(
                        corpus,
                        (kind, raw, limits) ->
                                SerializationReadResult.rejected(SerializationRejectionReason.DUPLICATE_KEY)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("positive control")
                .hasMessageContaining("was rejected as duplicate-key");
    }

    @Test
    void metadataLoader_shouldNotOpenOrParseRawInvalidJson() {
        // Arrange
        String manifest = """
                {
                  "schemaVersion": 1,
                  "limitProfiles": {
                    "test": {
                      "maxDocumentBytes": 128,
                      "maxNestingDepth": 8,
                      "maxStringLength": 64,
                      "maxNumericTokenLength": 8,
                      "maxCollectionEntries": 8
                    }
                  },
                  "positiveControls": [
                    {
                      "controlId": "JCF-POSITIVE-SESSIONS-999",
                      "documentKind": "agent-session",
                      "resource": "conformance/rejections/v1/valid/session.json",
                      "limitProfile": "test"
                    },
                    {
                      "controlId": "JCF-POSITIVE-WORKFLOWS-999",
                      "documentKind": "workflow-checkpoint",
                      "resource": "conformance/rejections/v1/valid/checkpoint.json",
                      "limitProfile": "test"
                    }
                  ],
                  "cases": [{
                    "caseId": "JCF-REJECTIONS-SESSIONS-999",
                    "documentKind": "agent-session",
                    "reason": "duplicate-key",
                    "resource": "conformance/rejections/v1/sessions/raw.json",
                    "limitProfile": "test"
                  }]
                }
                """;
        AtomicInteger rawOpenCount = new AtomicInteger();

        // Act
        SerializationRejectionCorpus corpus = new SerializationRejectionCorpusLoader()
                .load(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)), ignored -> {
                    rawOpenCount.incrementAndGet();
                    return new ByteArrayInputStream("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8));
                });

        // Assert
        assertThat(corpus.cases()).hasSize(1);
        assertThat(rawOpenCount).hasValue(0);
    }

    private static SerializationReadResult readWithPortableLimits(
            SerializationDocumentKind documentKind, byte[] utf8Json, SerializationLimits limits) throws IOException {
        if (utf8Json.length > limits.maxDocumentBytes()) {
            return SerializationReadResult.rejected(SerializationRejectionReason.DOCUMENT_BYTES);
        }
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(limits.maxDocumentBytes())
                .maxNestingDepth(limits.maxNestingDepth())
                .maxStringLength(limits.maxStringLength())
                .maxNumberLength(limits.maxNumericTokenLength())
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
        SerializationRejectionReason structuralRejection;
        try {
            structuralRejection = inspectStructure(factory, utf8Json, limits.maxCollectionEntries());
        } catch (CollectionLimitException exception) {
            return SerializationReadResult.rejected(SerializationRejectionReason.COLLECTION_ENTRIES);
        } catch (StreamConstraintsException exception) {
            return SerializationReadResult.rejected(classifyConstraint(exception));
        }
        if (structuralRejection != null) {
            return SerializationReadResult.rejected(structuralRejection);
        }
        JsonNode root;
        try {
            root = new ObjectMapper(factory).readTree(utf8Json);
        } catch (StreamConstraintsException exception) {
            return SerializationReadResult.rejected(classifyConstraint(exception));
        }
        if (!documentKind.wireName().equals(root.path("documentKind").textValue())) {
            return SerializationReadResult.rejected(SerializationRejectionReason.WRONG_DOCUMENT_KIND);
        }
        if (!root.path("payloadVersion").isInt() || root.path("payloadVersion").intValue() != 1) {
            return SerializationReadResult.rejected(SerializationRejectionReason.UNSUPPORTED_PAYLOAD_VERSION);
        }
        return SerializationReadResult.accepted();
    }

    private static SerializationRejectionReason inspectStructure(
            JsonFactory factory, byte[] utf8Json, int maxCollectionEntries) throws IOException {
        Deque<ContainerScan> containers = new ArrayDeque<>();
        int rootValues = 0;
        try (JsonParser parser = factory.createParser(utf8Json)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                switch (token) {
                    case START_OBJECT -> {
                        rootValues += registerValue(containers, maxCollectionEntries);
                        containers.push(ContainerScan.object());
                    }
                    case START_ARRAY -> {
                        rootValues += registerValue(containers, maxCollectionEntries);
                        containers.push(ContainerScan.array());
                    }
                    case END_OBJECT, END_ARRAY -> containers.pop();
                    case FIELD_NAME -> {
                        ContainerScan object = containers.getFirst();
                        parser.getTextLength();
                        if (!object.names.add(parser.currentName())) {
                            return SerializationRejectionReason.DUPLICATE_KEY;
                        }
                        if (++object.entries > maxCollectionEntries) {
                            return SerializationRejectionReason.COLLECTION_ENTRIES;
                        }
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        rootValues += registerValue(containers, maxCollectionEntries);
                        parser.getTextLength();
                        if (parser.isNaN()) {
                            return SerializationRejectionReason.NON_FINITE_NUMBER;
                        }
                    }
                    case VALUE_STRING -> {
                        rootValues += registerValue(containers, maxCollectionEntries);
                        parser.getTextLength();
                    }
                    case VALUE_TRUE, VALUE_FALSE, VALUE_NULL ->
                        rootValues += registerValue(containers, maxCollectionEntries);
                    default -> throw new IOException("Unexpected JSON token " + token + ".");
                }
            }
        }
        if (rootValues != 1 || !containers.isEmpty()) {
            throw new IOException("empty, trailing, or unclosed JSON");
        }
        return null;
    }

    private static int registerValue(Deque<ContainerScan> containers, int maxCollectionEntries) throws IOException {
        if (containers.isEmpty()) {
            return 1;
        }
        ContainerScan parent = containers.getFirst();
        if (!parent.object && ++parent.entries > maxCollectionEntries) {
            throw new CollectionLimitException();
        }
        return 0;
    }

    private static SerializationRejectionReason classifyConstraint(StreamConstraintsException exception)
            throws StreamConstraintsException {
        String message = exception.getOriginalMessage();
        if (message != null && message.startsWith("Document nesting depth (")) {
            return SerializationRejectionReason.NESTING_DEPTH;
        }
        if (message != null && (message.startsWith("String value length (") || message.startsWith("Name length ("))) {
            return SerializationRejectionReason.STRING_LENGTH;
        }
        if (message != null && message.startsWith("Number value length (")) {
            return SerializationRejectionReason.NUMERIC_TOKEN_LENGTH;
        }
        throw exception;
    }

    private static final class ContainerScan {
        private final boolean object;

        private final Set<String> names;

        private int entries;

        private ContainerScan(boolean object) {
            this.object = object;
            names = object ? new HashSet<>() : Set.of();
        }

        private static ContainerScan object() {
            return new ContainerScan(true);
        }

        private static ContainerScan array() {
            return new ContainerScan(false);
        }
    }

    private static final class CollectionLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
