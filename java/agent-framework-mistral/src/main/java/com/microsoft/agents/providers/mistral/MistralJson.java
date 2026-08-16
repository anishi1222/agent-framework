// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class MistralJson {
    private final MistralChatClientOptions options;

    private final JsonFactory factory;

    MistralJson(MistralChatClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(options.maxResponseBytes())
                        .maxNestingDepth(options.maxNestingDepth())
                        .maxStringLength(options.maxStringLength())
                        .maxNameLength(options.maxStringLength())
                        .maxNumberLength(1_000)
                        .build())
                .build();
    }

    byte[] writeRequest(StateValue value) {
        Objects.requireNonNull(value, "value");
        validate(value, 1);
        try {
            LimitedOutput output = new LimitedOutput(options.maxRequestBytes());
            try (JsonGenerator generator = factory.createGenerator(output)) {
                write(generator, value);
            }
            return output.toByteArray();
        } catch (LimitExceeded exception) {
            throw failure("request_too_large");
        } catch (IOException exception) {
            throw failure("request_encoding");
        }
    }

    String writeValue(StateValue value) {
        try {
            LimitedOutput output = new LimitedOutput(options.maxEventBytes());
            try (JsonGenerator generator = factory.createGenerator(output)) {
                write(generator, value);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (LimitExceeded exception) {
            throw failure("value_too_large");
        } catch (IOException exception) {
            throw failure("value_encoding");
        }
    }

    StateValue parseResponse(byte[] bytes) {
        return parse(bytes, options.maxResponseBytes());
    }

    StateValue parseEvent(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return parse(bytes, options.maxEventBytes());
    }

    private StateValue parse(byte[] bytes, int maximumBytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > maximumBytes) {
            throw failure("response_too_large");
        }
        try (JsonParser parser = factory.createParser(bytes)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw failure("empty_json");
            }
            StateValue value = read(parser, first);
            if (parser.nextToken() != null) {
                throw failure("trailing_json");
            }
            return value;
        } catch (MistralProviderException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw failure("json_limit");
        } catch (JsonParseException exception) {
            throw failure("malformed_json");
        } catch (IOException exception) {
            throw failure("json_io");
        }
    }

    private StateValue read(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> StateValue.string(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                if (parser.isNaN()) {
                    throw failure("non_finite_number");
                }
                yield StateValue.number(parser.getDecimalValue());
            }
            case VALUE_TRUE -> StateValue.bool(true);
            case VALUE_FALSE -> StateValue.bool(false);
            case VALUE_NULL -> StateValue.nullValue();
            default -> throw failure("unexpected_json_token");
        };
    }

    private StateValue.ObjectValue readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null || token != JsonToken.FIELD_NAME) {
                throw failure("malformed_object");
            }
            if (values.size() >= options.maxCollectionEntries()) {
                throw failure("collection_limit");
            }
            String name = parser.currentName();
            if (name == null || name.isBlank() || values.containsKey(name)) {
                throw failure(values.containsKey(name) ? "duplicate_key" : "invalid_key");
            }
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw failure("missing_value");
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
                throw failure("malformed_array");
            }
            if (values.size() >= options.maxCollectionEntries()) {
                throw failure("collection_limit");
            }
            values.add(read(parser, token));
        }
        return StateValue.array(values);
    }

    private static void write(JsonGenerator generator, StateValue value) throws IOException {
        switch (value) {
            case StateValue.ObjectValue object -> {
                generator.writeStartObject();
                for (Map.Entry<String, StateValue> entry : object.values().entrySet()) {
                    generator.writeFieldName(entry.getKey());
                    write(generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            case StateValue.ArrayValue array -> {
                generator.writeStartArray();
                for (StateValue item : array.values()) {
                    write(generator, item);
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
        if (depth > options.maxNestingDepth()) {
            throw failure("json_depth_limit");
        }
        switch (value) {
            case StateValue.ObjectValue object -> {
                if (object.values().size() > options.maxCollectionEntries()) {
                    throw failure("collection_limit");
                }
                object.values().forEach((key, item) -> {
                    requireString(key);
                    validate(item, depth + 1);
                });
            }
            case StateValue.ArrayValue array -> {
                if (array.values().size() > options.maxCollectionEntries()) {
                    throw failure("collection_limit");
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
        if (value.length() > options.maxStringLength()) {
            throw failure("string_limit");
        }
    }

    private static void requireNumber(BigDecimal value) {
        if (value.toString().length() > 1_000) {
            throw failure("number_limit");
        }
    }

    private static MistralProviderException failure(String kind) {
        return new MistralProviderException(kind, null, null, "invalid_json");
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
