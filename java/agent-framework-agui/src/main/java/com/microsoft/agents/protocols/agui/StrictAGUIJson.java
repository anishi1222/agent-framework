// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class StrictAGUIJson {
    private final AGUILimits limits;

    private final JsonFactory factory;

    StrictAGUIJson(AGUILimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(limits.maxRequestBytes())
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

    StateValue parse(byte[] utf8Json) {
        Objects.requireNonNull(utf8Json, "utf8Json");
        if (utf8Json.length > limits.maxRequestBytes()) {
            throw limit("AG-UI JSON exceeds maxRequestBytes.");
        }
        try (JsonParser parser = factory.createParser(utf8Json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw malformed("AG-UI JSON is empty.");
            }
            StateValue value = readValue(parser, first);
            if (parser.nextToken() != null) {
                throw malformed("AG-UI JSON contains trailing content.");
            }
            return value;
        } catch (AGUIProtocolException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw limit("AG-UI JSON exceeds a parser limit.", exception);
        } catch (JsonParseException exception) {
            throw malformed("AG-UI input is not valid JSON.", exception);
        } catch (IOException exception) {
            throw malformed("Unable to parse AG-UI JSON.", exception);
        }
    }

    byte[] write(StateValue value) {
        Objects.requireNonNull(value, "value");
        validate(value, 1);
        try {
            LimitedByteArrayOutputStream output = new LimitedByteArrayOutputStream(limits.maxResponseBytes());
            try (JsonGenerator generator = factory.createGenerator(output)) {
                writeValue(generator, value);
            }
            return output.toByteArray();
        } catch (ResponseLimitExceededException exception) {
            throw limit("Encoded AG-UI JSON exceeds maxResponseBytes.", exception);
        } catch (AGUIProtocolException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AGUIProtocolException(AGUIErrorCode.TRANSPORT, "Unable to encode AG-UI JSON.", exception);
        }
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
            default -> throw malformed("Unexpected JSON token.");
        };
    }

    private StateValue.ObjectValue readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null) {
                throw malformed("JSON object is not closed.");
            }
            if (token != JsonToken.FIELD_NAME) {
                throw malformed("Expected a JSON object member name.");
            }
            if (values.size() >= limits.maxCollectionEntries()) {
                throw limit("JSON object exceeds maxCollectionEntries.");
            }
            String name = parser.currentName();
            requireString(name);
            if (name.isBlank()) {
                throw malformed("JSON object member names must not be blank.");
            }
            if (values.containsKey(name)) {
                throw malformed("JSON object contains a duplicate member.");
            }
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw malformed("JSON object member has no value.");
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
            if (values.size() >= limits.maxCollectionEntries()) {
                throw limit("JSON array exceeds maxCollectionEntries.");
            }
            values.add(readValue(parser, token));
        }
        return StateValue.array(values);
    }

    private StateValue.NumberValue readNumber(JsonParser parser) throws IOException {
        if (parser.isNaN()) {
            throw malformed("JSON contains a non-finite number.");
        }
        if (parser.getTextLength() > limits.maxNumericTokenLength()) {
            throw limit("JSON number exceeds maxNumericTokenLength.");
        }
        return StateValue.number(parser.getDecimalValue());
    }

    private void writeValue(JsonGenerator generator, StateValue value) throws IOException {
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
        if (depth > limits.maxNestingDepth()) {
            throw limit("AG-UI JSON exceeds maxNestingDepth.");
        }
        switch (value) {
            case StateValue.ObjectValue object -> {
                if (object.values().size() > limits.maxCollectionEntries()) {
                    throw limit("AG-UI object exceeds maxCollectionEntries.");
                }
                object.values().forEach((key, child) -> {
                    requireString(key);
                    validate(child, depth + 1);
                });
            }
            case StateValue.ArrayValue array -> {
                if (array.values().size() > limits.maxCollectionEntries()) {
                    throw limit("AG-UI array exceeds maxCollectionEntries.");
                }
                array.values().forEach(child -> validate(child, depth + 1));
            }
            case StateValue.StringValue string -> requireString(string.value());
            case StateValue.NumberValue number -> requireNumber(number.value());
            case StateValue.BooleanValue _, StateValue.NullValue _ -> {
                // No additional scalar bound.
            }
        }
    }

    private void requireString(String value) {
        if (value.length() > limits.maxStringLength()) {
            throw limit("AG-UI string exceeds maxStringLength.");
        }
    }

    private void requireNumber(BigDecimal value) {
        if (value.toString().length() > limits.maxNumericTokenLength()) {
            throw limit("AG-UI number exceeds maxNumericTokenLength.");
        }
    }

    private static AGUIProtocolException malformed(String message) {
        return new AGUIProtocolException(AGUIErrorCode.MALFORMED_INPUT, message);
    }

    private static AGUIProtocolException malformed(String message, Throwable cause) {
        return new AGUIProtocolException(AGUIErrorCode.MALFORMED_INPUT, message, cause);
    }

    private static AGUIProtocolException limit(String message) {
        return new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, message);
    }

    private static AGUIProtocolException limit(String message, Throwable cause) {
        return new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, message, cause);
    }

    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final long maximumBytes;

        private LimitedByteArrayOutputStream(long maximumBytes) {
            super((int) Math.min(8 * 1024L, maximumBytes));
            this.maximumBytes = maximumBytes;
        }

        @Override
        public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            super.write(values, offset, length);
        }

        private void requireCapacity(int additionalBytes) {
            if (additionalBytes > maximumBytes - count) {
                throw new ResponseLimitExceededException();
            }
        }
    }

    private static final class ResponseLimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
