// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AGUIValidation {
    private AGUIValidation() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw invalid(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        return value == null ? null : nonBlank(value, name);
    }

    static BigDecimal timestamp(BigDecimal value) {
        return value;
    }

    static <T> List<T> list(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return List.copyOf(copy);
    }

    static <K, V> Map<K, V> map(Map<? extends K, ? extends V> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        values.forEach((key, value) ->
                copy.put(Objects.requireNonNull(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    static StateValue state(StateValue value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static StateValue.ObjectValue object(StateValue.ObjectValue value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_MODEL, message);
    }
}
