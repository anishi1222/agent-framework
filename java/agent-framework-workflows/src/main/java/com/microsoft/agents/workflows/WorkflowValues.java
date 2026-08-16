// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkflowValues {
    private WorkflowValues() {}

    static StateValue toStateValue(Object value) {
        if (value == null) {
            return StateValue.nullValue();
        }
        if (value instanceof StateValue stateValue) {
            return stateValue;
        }
        if (value instanceof String string) {
            return StateValue.string(string);
        }
        if (value instanceof Boolean bool) {
            return StateValue.bool(bool);
        }
        if (value instanceof BigDecimal decimal) {
            return StateValue.number(decimal);
        }
        if (value instanceof BigInteger integer) {
            return StateValue.integer(integer);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return StateValue.integer(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new WorkflowValueEncodingException("Workflow event data cannot contain a non-finite number.");
            }
            return StateValue.number(BigDecimal.valueOf(number));
        }
        if (value instanceof FanInInput fanInInput) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            fanInInput.rawValues().forEach((sourceId, member) -> values.put(sourceId.value(), toStateValue(member)));
            return StateValue.object(
                    Map.of("epoch", StateValue.integer(fanInInput.epoch()), "values", StateValue.object(values)));
        }
        if (value instanceof List<?> list) {
            ArrayList<StateValue> values = new ArrayList<>(list.size());
            list.forEach(item -> values.add(toStateValue(item)));
            return StateValue.array(values);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
            map.forEach((key, member) -> {
                if (!(key instanceof String string) || string.isBlank()) {
                    throw new WorkflowValueEncodingException("Workflow event map keys must be non-blank strings.");
                }
                values.put(string, toStateValue(member));
            });
            return StateValue.object(values);
        }
        throw new WorkflowValueEncodingException(
                "Workflow value type '" + value.getClass().getName()
                        + "' is not JSON-shaped; configure WorkflowRunOptions.Builder.valueEncoder(...).");
    }
}
