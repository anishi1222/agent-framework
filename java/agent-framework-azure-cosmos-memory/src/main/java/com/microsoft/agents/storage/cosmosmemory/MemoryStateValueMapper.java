// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MemoryStateValueMapper {
    private MemoryStateValueMapper() {}

    static Object toObject(StateValue value) {
        return toObject(value, 0, new int[1]);
    }

    private static Object toObject(StateValue value, int depth, int[] entries) {
        requireDepth(depth);
        return switch (value) {
            case StateValue.NullValue _ -> null;
            case StateValue.BooleanValue bool -> bool.value();
            case StateValue.NumberValue number -> {
                requireString(number.value().toPlainString(), "numeric token");
                yield number.value();
            }
            case StateValue.StringValue string -> {
                requireString(string.value(), "string");
                yield string.value();
            }
            case StateValue.ArrayValue array -> {
                requireEntries(entries, array.values().size());
                ArrayList<Object> values = new ArrayList<>(array.values().size());
                array.values().forEach(item -> values.add(toObject(item, depth + 1, entries)));
                yield java.util.Collections.unmodifiableList(values);
            }
            case StateValue.ObjectValue object -> {
                requireEntries(entries, object.values().size());
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> {
                    requireString(key, "object key");
                    values.put(key, toObject(item, depth + 1, entries));
                });
                yield java.util.Collections.unmodifiableMap(values);
            }
        };
    }

    static StateValue fromObject(Object value) {
        return fromObject(value, 0, new int[1]);
    }

    private static StateValue fromObject(Object value, int depth, int[] entries) {
        requireDepth(depth);
        if (value == null) {
            return StateValue.nullValue();
        }
        if (value instanceof Boolean bool) {
            return StateValue.bool(bool);
        }
        if (value instanceof String string) {
            requireString(string, "string");
            return StateValue.string(string);
        }
        if (value instanceof BigDecimal decimal) {
            requireString(decimal.toPlainString(), "numeric token");
            return StateValue.number(decimal);
        }
        if (value instanceof Number number) {
            requireString(number.toString(), "numeric token");
            return StateValue.number(new BigDecimal(number.toString()));
        }
        if (value instanceof List<?> list) {
            requireEntries(entries, list.size());
            ArrayList<StateValue> values = new ArrayList<>(list.size());
            list.forEach(item -> values.add(fromObject(item, depth + 1, entries)));
            return StateValue.array(values);
        }
        if (value instanceof Map<?, ?> map) {
            requireEntries(entries, map.size());
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String string) || string.isBlank()) {
                    throw malformed("Memory metadata contains an invalid object key.");
                }
                requireString(string, "object key");
                values.put(string, fromObject(item, depth + 1, entries));
            });
            return StateValue.object(values);
        }
        throw malformed("Memory metadata contains an unsupported JSON representation.");
    }

    private static void requireDepth(int depth) {
        if (depth > CosmosMemoryOptions.MAX_METADATA_DEPTH) {
            throw malformed("Memory metadata exceeds the maximum nesting depth.");
        }
    }

    private static void requireEntries(int[] count, int additional) {
        count[0] += additional;
        if (count[0] > CosmosMemoryOptions.MAX_METADATA_ENTRIES) {
            throw malformed("Memory metadata exceeds the maximum collection entries.");
        }
    }

    private static void requireString(String value, String name) {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CosmosMemoryOptions.MAX_STRING_BYTES) {
            throw malformed("Memory metadata " + name + " exceeds the maximum string bytes.");
        }
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }
}
