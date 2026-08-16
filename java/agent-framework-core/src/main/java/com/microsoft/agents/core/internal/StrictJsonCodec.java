// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes and decodes bounded JSON directly to framework-owned {@link StateValue} values.
 *
 * <p>This is an internal cross-module utility. It performs no polymorphic binding, rejects duplicate
 * keys, non-finite numbers, and trailing content, and enforces limits while parsing.
 */
public final class StrictJsonCodec {
    private final int maxWriteBytes;

    private final int maxReadBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxNumericTokenLength;

    private final int maxCollectionEntries;

    private final JsonFactory factory;

    /**
     * Creates a strict codec.
     *
     * @param maxWriteBytes maximum encoded output bytes
     * @param maxReadBytes maximum input bytes
     * @param maxNestingDepth maximum object and array nesting
     * @param maxStringLength maximum decoded string and member-name length
     * @param maxNumericTokenLength maximum numeric token length
     * @param maxCollectionEntries maximum members per object or elements per array
     */
    public StrictJsonCodec(
            int maxWriteBytes,
            int maxReadBytes,
            int maxNestingDepth,
            int maxStringLength,
            int maxNumericTokenLength,
            int maxCollectionEntries) {
        this.maxWriteBytes = positive(maxWriteBytes, "maxWriteBytes");
        this.maxReadBytes = positive(maxReadBytes, "maxReadBytes");
        this.maxNestingDepth = positive(maxNestingDepth, "maxNestingDepth");
        this.maxStringLength = positive(maxStringLength, "maxStringLength");
        this.maxNumericTokenLength = positive(maxNumericTokenLength, "maxNumericTokenLength");
        this.maxCollectionEntries = positive(maxCollectionEntries, "maxCollectionEntries");
        factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(maxReadBytes)
                        .maxNestingDepth(maxNestingDepth)
                        .maxStringLength(maxStringLength)
                        .maxNameLength(maxStringLength)
                        .maxNumberLength(maxNumericTokenLength)
                        .build())
                .build();
    }

    /**
     * Parses one complete UTF-8 JSON document.
     *
     * @param utf8Json encoded JSON
     * @return framework-owned JSON value
     * @throws SerializationException when JSON is malformed or exceeds a configured limit
     */
    public StateValue parse(byte[] utf8Json) {
        Objects.requireNonNull(utf8Json, "utf8Json");
        if (utf8Json.length > maxReadBytes) {
            throw failure(SerializationError.DOCUMENT_BYTES, "JSON exceeds maxReadBytes.");
        }
        try (JsonParser parser = factory.createParser(utf8Json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw failure(SerializationError.MALFORMED_DOCUMENT, "JSON is empty.");
            }
            StateValue value = read(parser, first);
            if (parser.nextToken() != null) {
                throw failure(SerializationError.TRAILING_CONTENT, "JSON contains trailing content.");
            }
            return value;
        } catch (SerializationException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw constraintFailure(exception);
        } catch (JsonParseException exception) {
            throw failure(SerializationError.MALFORMED_DOCUMENT, "Input is not valid JSON.", exception);
        } catch (IOException exception) {
            throw failure(SerializationError.MALFORMED_DOCUMENT, "Unable to parse JSON.", exception);
        }
    }

    /**
     * Encodes one framework-owned JSON value.
     *
     * @param value JSON-shaped value
     * @return UTF-8 JSON
     * @throws SerializationException when the value exceeds a configured limit
     */
    public byte[] write(StateValue value) {
        Objects.requireNonNull(value, "value");
        validate(value, 1);
        try {
            LimitedOutput output = new LimitedOutput(maxWriteBytes);
            try (JsonGenerator generator = factory.createGenerator(output)) {
                writeValue(generator, value);
            }
            return output.toByteArray();
        } catch (LimitExceeded exception) {
            throw failure(SerializationError.DOCUMENT_BYTES, "Encoded JSON exceeds maxWriteBytes.");
        } catch (IOException exception) {
            throw failure(SerializationError.MALFORMED_DOCUMENT, "Unable to encode JSON.", exception);
        }
    }

    private StateValue read(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> StateValue.string(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                if (parser.isNaN()) {
                    throw failure(SerializationError.NON_FINITE_NUMBER, "JSON contains a non-finite number.");
                }
                yield StateValue.number(parser.getDecimalValue());
            }
            case VALUE_TRUE -> StateValue.bool(true);
            case VALUE_FALSE -> StateValue.bool(false);
            case VALUE_NULL -> StateValue.nullValue();
            default -> throw failure(SerializationError.MALFORMED_DOCUMENT, "Unexpected JSON token.");
        };
    }

    private StateValue.ObjectValue readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null || token != JsonToken.FIELD_NAME) {
                throw failure(SerializationError.MALFORMED_DOCUMENT, "JSON object is not closed.");
            }
            if (values.size() >= maxCollectionEntries) {
                throw failure(SerializationError.COLLECTION_ENTRIES, "JSON object exceeds maxCollectionEntries.");
            }
            String name = parser.currentName();
            requireString(name);
            if (name == null || name.isBlank()) {
                throw failure(SerializationError.MALFORMED_DOCUMENT, "JSON member names must not be blank.");
            }
            if (values.containsKey(name)) {
                throw failure(SerializationError.DUPLICATE_KEY, "JSON object contains a duplicate member.");
            }
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw failure(SerializationError.MALFORMED_DOCUMENT, "JSON member has no value.");
            }
            values.put(name, read(parser, valueToken));
        }
        return StateValue.object(values);
    }

    private StateValue.ArrayValue readArray(JsonParser parser) throws IOException {
        ArrayList<StateValue> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw failure(SerializationError.MALFORMED_DOCUMENT, "JSON array is not closed.");
            }
            if (values.size() >= maxCollectionEntries) {
                throw failure(SerializationError.COLLECTION_ENTRIES, "JSON array exceeds maxCollectionEntries.");
            }
            values.add(read(parser, token));
        }
        return StateValue.array(values);
    }

    private static void writeValue(JsonGenerator generator, StateValue value) throws IOException {
        switch (value) {
            case StateValue.ObjectValue object -> {
                generator.writeStartObject();
                for (Map.Entry<String, StateValue> entry : object.values().entrySet()) {
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
            case StateValue.NullValue _ -> generator.writeNull();
        }
    }

    private void validate(StateValue value, int depth) {
        if (depth > maxNestingDepth) {
            throw failure(SerializationError.NESTING_DEPTH, "JSON exceeds maxNestingDepth.");
        }
        switch (value) {
            case StateValue.ObjectValue object -> {
                if (object.values().size() > maxCollectionEntries) {
                    throw failure(SerializationError.COLLECTION_ENTRIES, "JSON object exceeds maxCollectionEntries.");
                }
                object.values().forEach((key, item) -> {
                    requireString(key);
                    validate(item, depth + 1);
                });
            }
            case StateValue.ArrayValue array -> {
                if (array.values().size() > maxCollectionEntries) {
                    throw failure(SerializationError.COLLECTION_ENTRIES, "JSON array exceeds maxCollectionEntries.");
                }
                array.values().forEach(item -> validate(item, depth + 1));
            }
            case StateValue.StringValue string -> requireString(string.value());
            case StateValue.NumberValue number -> requireNumber(number.value());
            case StateValue.BooleanValue _, StateValue.NullValue _ -> {
                // Scalars require no additional checks.
            }
        }
    }

    private void requireString(String value) {
        if (value != null && value.length() > maxStringLength) {
            throw failure(SerializationError.STRING_LENGTH, "JSON string exceeds maxStringLength.");
        }
    }

    private void requireNumber(BigDecimal value) {
        if (value.toString().length() > maxNumericTokenLength) {
            throw failure(SerializationError.NUMERIC_TOKEN_LENGTH, "JSON number exceeds maxNumericTokenLength.");
        }
    }

    private SerializationException constraintFailure(StreamConstraintsException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        SerializationError error;
        if (message.contains("Nesting depth")) {
            error = SerializationError.NESTING_DEPTH;
        } else if (message.contains("String value") || message.contains("Name length")) {
            error = SerializationError.STRING_LENGTH;
        } else if (message.contains("Number value")) {
            error = SerializationError.NUMERIC_TOKEN_LENGTH;
        } else {
            error = SerializationError.DOCUMENT_BYTES;
        }
        return failure(error, "JSON exceeds a parser limit.", exception);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    private static SerializationException failure(SerializationError error, String message) {
        return new SerializationException(error, message);
    }

    private static SerializationException failure(SerializationError error, String message, Throwable cause) {
        return new SerializationException(error, message, cause);
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int maximum;

        private LimitedOutput(int maximum) {
            super(Math.min(maximum, 8 * 1024));
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int length) {
            if (length > maximum - count) {
                throw new LimitExceeded();
            }
        }
    }

    private static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
