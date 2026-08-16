// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Implements safe, deterministic, versioned state JSON without exposing Jackson in public APIs.
 *
 * <p>Default typing is never enabled and Java class names are never emitted. Parsing applies byte,
 * depth, string, number, and per-collection limits; rejects duplicate keys, non-finite numbers, and
 * trailing content; and accepts unknown additive envelope properties only after applying the same
 * limits to them.
 */
public final class JsonStateSerializer implements StateSerializer {
    private final SerializationLimits limits;

    private final Map<DocumentKind, Set<Integer>> supportedVersions;

    private final JsonFactory factory;

    /**
     * Creates a serializer supporting payload version 1 for every current document kind.
     *
     * @param limits mandatory parser and writer limits
     */
    public JsonStateSerializer(SerializationLimits limits) {
        this(
                limits,
                Map.of(
                        DocumentKind.AGENT_SESSION,
                        Set.of(1),
                        DocumentKind.HISTORY_MESSAGE,
                        Set.of(1),
                        DocumentKind.WORKFLOW_CHECKPOINT,
                        Set.of(1)));
    }

    /**
     * Creates a serializer with explicit supported versions per document kind.
     *
     * @param limits mandatory parser and writer limits
     * @param supportedVersions non-empty positive version sets
     */
    public JsonStateSerializer(SerializationLimits limits, Map<DocumentKind, Set<Integer>> supportedVersions) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.supportedVersions = copySupportedVersions(supportedVersions);
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(limits.maxDocumentBytes())
                .maxNestingDepth(limits.maxNestingDepth())
                .maxStringLength(limits.maxStringLength())
                .maxNameLength(limits.maxStringLength())
                .maxNumberLength(limits.maxNumericTokenLength())
                .build();
        factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
    }

    @Override
    public byte[] write(StateEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        requireSupportedVersion(envelope.documentKind(), envelope.payloadVersion());
        requireCollectionLimit(4);
        requireStringLimit("format");
        requireStringLimit("documentKind");
        requireStringLimit("payloadVersion");
        requireStringLimit("payload");
        requireStringLimit(StateEnvelope.FORMAT);
        requireStringLimit(envelope.documentKind().value());
        requireNumberLimit(BigDecimal.valueOf(envelope.payloadVersion()));
        validateValue(envelope.payload(), 2);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (JsonGenerator generator = factory.createGenerator(output)) {
                LinkedHashMap<String, StateValue> document = new LinkedHashMap<>();
                document.put("format", StateValue.string(StateEnvelope.FORMAT));
                document.put(
                        "documentKind",
                        StateValue.string(envelope.documentKind().value()));
                document.put("payloadVersion", StateValue.integer(envelope.payloadVersion()));
                document.put("payload", envelope.payload());
                writeValue(generator, StateValue.object(document));
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > limits.maxDocumentBytes()) {
                throw new SerializationException(
                        SerializationError.DOCUMENT_BYTES,
                        "Encoded document exceeds maxDocumentBytes " + limits.maxDocumentBytes() + ".");
            }
            return bytes;
        } catch (IOException exception) {
            throw new SerializationException(
                    SerializationError.MALFORMED_DOCUMENT, "Unable to encode state JSON.", exception);
        }
    }

    @Override
    public StateEnvelope read(byte[] utf8Json, DocumentKind expectedKind) {
        Objects.requireNonNull(utf8Json, "utf8Json");
        Objects.requireNonNull(expectedKind, "expectedKind");
        if (utf8Json.length > limits.maxDocumentBytes()) {
            throw new SerializationException(
                    SerializationError.DOCUMENT_BYTES,
                    "Document exceeds maxDocumentBytes " + limits.maxDocumentBytes() + ".");
        }
        try (JsonParser parser = factory.createParser(utf8Json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw malformed("State document is empty.");
            }
            StateValue root = readValue(parser, first);
            if (parser.nextToken() != null) {
                throw new SerializationException(
                        SerializationError.TRAILING_CONTENT, "State document contains trailing JSON content.");
            }
            return readEnvelope(root, expectedKind);
        } catch (SerializationException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw JacksonStreamConstraintMapper.map(exception);
        } catch (JsonParseException exception) {
            throw mapParse(exception);
        } catch (IOException exception) {
            throw new SerializationException(
                    SerializationError.MALFORMED_DOCUMENT, "Unable to read state JSON.", exception);
        }
    }

    @Override
    public SerializationLimits limits() {
        return limits;
    }

    private StateEnvelope readEnvelope(StateValue root, DocumentKind expectedKind) {
        if (!(root instanceof StateValue.ObjectValue object)) {
            throw malformed("State document must be an object.");
        }
        String format = requireString(object, "format");
        if (!StateEnvelope.FORMAT.equals(format)) {
            throw malformed("Unsupported state format '" + format + "'.");
        }
        String kindValue = requireString(object, "documentKind");
        DocumentKind actualKind = DocumentKind.fromValue(kindValue);
        if (actualKind != expectedKind) {
            throw new SerializationException(
                    SerializationError.WRONG_DOCUMENT_KIND,
                    "Document kind " + actualKind.value() + " does not match reader " + expectedKind.value() + ".");
        }
        int payloadVersion = requirePositiveInt(object, "payloadVersion");
        requireSupportedVersion(actualKind, payloadVersion);
        return new StateEnvelope(format, actualKind, payloadVersion, object.require("payload"));
    }

    private StateValue readValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> StateValue.string(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> readNumber(parser);
            case VALUE_TRUE -> StateValue.bool(true);
            case VALUE_FALSE -> StateValue.bool(false);
            case VALUE_NULL -> StateValue.nullValue();
            default -> throw malformed("Unexpected JSON token " + token + ".");
        };
    }

    private StateValue.ObjectValue readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        int entries = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) {
                throw malformed("Expected an object field name.");
            }
            entries++;
            requireCollectionLimit(entries);
            String name = parser.currentName();
            requireStringLimit(name);
            if (name.isBlank()) {
                throw malformed("JSON object member names must not be blank.");
            }
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw malformed("Object field '" + name + "' has no value.");
            }
            if (values.containsKey(name)) {
                throw new SerializationException(
                        SerializationError.DUPLICATE_KEY, "JSON object contains duplicate key '" + name + "'.");
            }
            values.put(name, readValue(parser, valueToken));
        }
        return StateValue.object(values);
    }

    private StateValue.ArrayValue readArray(JsonParser parser) throws IOException {
        ArrayList<StateValue> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw malformed("JSON array is not closed.");
            }
            requireCollectionLimit(values.size() + 1);
            values.add(readValue(parser, token));
        }
        return StateValue.array(values);
    }

    private StateValue.NumberValue readNumber(JsonParser parser) throws IOException {
        if (parser.isNaN()) {
            throw new SerializationException(
                    SerializationError.NON_FINITE_NUMBER, "State JSON contains a non-finite number.");
        }
        if (parser.getTextLength() > limits.maxNumericTokenLength()) {
            throw new SerializationException(
                    SerializationError.NUMERIC_TOKEN_LENGTH,
                    "Number exceeds maxNumericTokenLength " + limits.maxNumericTokenLength() + ".");
        }
        return StateValue.number(parser.getDecimalValue());
    }

    private void requireCollectionLimit(int entries) {
        if (entries > limits.maxCollectionEntries()) {
            throw new SerializationException(
                    SerializationError.COLLECTION_ENTRIES,
                    "JSON collection exceeds maxCollectionEntries " + limits.maxCollectionEntries() + ".");
        }
    }

    private void writeValue(JsonGenerator generator, StateValue value) throws IOException {
        switch (value) {
            case StateValue.ObjectValue object -> {
                generator.writeStartObject();
                for (Map.Entry<String, StateValue> entry : new TreeMap<>(object.values()).entrySet()) {
                    generator.writeFieldName(entry.getKey());
                    writeValue(generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            case StateValue.ArrayValue array -> {
                generator.writeStartArray();
                for (StateValue item : array.values()) {
                    writeValue(generator, item);
                }
                generator.writeEndArray();
            }
            case StateValue.StringValue string -> generator.writeString(string.value());
            case StateValue.NumberValue number -> generator.writeNumber(number.value());
            case StateValue.BooleanValue bool -> generator.writeBoolean(bool.value());
            case StateValue.NullValue nullValue -> {
                Objects.requireNonNull(nullValue, "nullValue");
                generator.writeNull();
            }
        }
    }

    private void validateValue(StateValue value, int depth) {
        if (depth > limits.maxNestingDepth()) {
            throw new SerializationException(
                    SerializationError.NESTING_DEPTH,
                    "State value exceeds maxNestingDepth " + limits.maxNestingDepth() + ".");
        }
        switch (value) {
            case StateValue.ObjectValue object -> {
                requireCollectionLimit(object.values().size());
                object.values().forEach((key, member) -> {
                    requireStringLimit(key);
                    validateValue(member, depth + 1);
                });
            }
            case StateValue.ArrayValue array -> {
                requireCollectionLimit(array.values().size());
                array.values().forEach(member -> validateValue(member, depth + 1));
            }
            case StateValue.StringValue string -> requireStringLimit(string.value());
            case StateValue.NumberValue number -> requireNumberLimit(number.value());
            case StateValue.BooleanValue bool -> {
                Objects.requireNonNull(bool, "bool");
            }
            case StateValue.NullValue nullValue -> {
                Objects.requireNonNull(nullValue, "nullValue");
            }
        }
    }

    private void requireStringLimit(String value) {
        if (value.length() > limits.maxStringLength()) {
            throw new SerializationException(
                    SerializationError.STRING_LENGTH,
                    "String exceeds maxStringLength " + limits.maxStringLength() + ".");
        }
    }

    private void requireNumberLimit(BigDecimal value) {
        if (value.toString().length() > limits.maxNumericTokenLength()) {
            throw new SerializationException(
                    SerializationError.NUMERIC_TOKEN_LENGTH,
                    "Number exceeds maxNumericTokenLength " + limits.maxNumericTokenLength() + ".");
        }
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed("Envelope field '" + name + "' must be a string.");
    }

    private static int requirePositiveInt(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (!(value instanceof StateValue.NumberValue number)
                || number.value().scale() > 0
                || number.value().signum() <= 0) {
            throw malformed("Envelope field '" + name + "' must be a positive integer.");
        }
        try {
            return number.value().intValueExact();
        } catch (ArithmeticException exception) {
            throw malformed("Envelope field '" + name + "' is outside the supported integer range.", exception);
        }
    }

    private void requireSupportedVersion(DocumentKind kind, int version) {
        if (!supportedVersions.getOrDefault(kind, Set.of()).contains(version)) {
            throw new SerializationException(
                    SerializationError.UNSUPPORTED_PAYLOAD_VERSION,
                    "Unsupported " + kind.value() + " payload version " + version + ".");
        }
    }

    private static Map<DocumentKind, Set<Integer>> copySupportedVersions(Map<DocumentKind, Set<Integer>> versions) {
        Objects.requireNonNull(versions, "supportedVersions");
        EnumMap<DocumentKind, Set<Integer>> copy = new EnumMap<>(DocumentKind.class);
        versions.forEach((kind, values) -> {
            Objects.requireNonNull(kind, "supportedVersions key");
            Objects.requireNonNull(values, "supportedVersions value");
            if (values.isEmpty() || values.stream().anyMatch(version -> version == null || version <= 0)) {
                throw new ValidationException("Supported payload-version sets must contain positive integers.");
            }
            copy.put(kind, Set.copyOf(values));
        });
        return Map.copyOf(copy);
    }

    private static SerializationException mapParse(JsonParseException exception) {
        return new SerializationException(
                SerializationError.MALFORMED_DOCUMENT, "State document is not valid JSON.", exception);
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }

    private static SerializationException malformed(String message, Throwable cause) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message, cause);
    }
}
