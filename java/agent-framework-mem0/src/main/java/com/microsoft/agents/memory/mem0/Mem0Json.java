// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StrictJsonCodec;

final class Mem0Json {
    private final StrictJsonCodec codec;

    Mem0Json(Mem0LimitOptions limits) {
        codec = new StrictJsonCodec(
                limits.maxRequestBytes(),
                limits.maxResponseBytes(),
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                limits.maxNumericTokenLength(),
                limits.maxCollectionEntries());
    }

    byte[] writeRequest(StateValue value) {
        try {
            return codec.write(value);
        } catch (SerializationException exception) {
            throw new ValidationException("Mem0 request exceeds a configured JSON limit.");
        }
    }

    StateValue parseResponse(byte[] bytes, String operation) {
        try {
            return codec.parse(bytes);
        } catch (SerializationException exception) {
            throw failure(operation);
        }
    }

    static StateValue.ObjectValue object(StateValue value, String operation, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw failure(operation);
    }

    static StateValue.ArrayValue array(StateValue value, String operation, String name) {
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw failure(operation);
    }

    static StateValue.ArrayValue requiredArray(StateValue.ObjectValue object, String member, String operation) {
        StateValue value = object.values().get(member);
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw failure(operation);
    }

    static StateValue.ArrayValue optionalArray(StateValue.ObjectValue object, String member, String operation) {
        StateValue value = object.values().get(member);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw failure(operation);
    }

    static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String member, String operation) {
        StateValue value = object.values().get(member);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.ObjectValue nested) {
            return nested;
        }
        throw failure(operation);
    }

    static String requiredString(StateValue.ObjectValue object, String member, String operation) {
        String value = optionalString(object, member, operation);
        if (value == null || value.isBlank()) {
            throw failure(operation);
        }
        return value;
    }

    static String optionalString(StateValue.ObjectValue object, String member, String operation) {
        StateValue value = object.values().get(member);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw failure(operation);
    }

    static Double optionalDouble(StateValue.ObjectValue object, String member, String operation) {
        StateValue value = object.values().get(member);
        if (value == null || value instanceof StateValue.NullValue) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number) {
            return number.value().doubleValue();
        }
        throw failure(operation);
    }

    static Mem0StorageException failure(String operation) {
        return new Mem0StorageException(Mem0StorageException.Kind.DATA_CONTRACT, operation, null, null, null);
    }
}
