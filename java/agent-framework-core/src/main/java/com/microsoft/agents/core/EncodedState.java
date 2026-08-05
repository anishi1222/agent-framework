// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Stores an explicitly typed and independently versioned codec value.
 *
 * @param typeId stable registered codec identifier
 * @param codecVersion positive codec schema version
 * @param value JSON-shaped encoded value
 */
public record EncodedState(String typeId, int codecVersion, StateValue value) {
    /** Creates validated encoded state. */
    public EncodedState {
        typeId = CoreValidation.requireNonBlank(typeId, "typeId");
        if (codecVersion <= 0) {
            throw new ValidationException("codecVersion must be greater than zero.");
        }
        Objects.requireNonNull(value, "value");
    }

    /**
     * Converts this value to its stable JSON-shaped representation.
     *
     * @return object containing {@code typeId}, {@code codecVersion}, and {@code value}
     */
    public StateValue.ObjectValue toStateValue() {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("typeId", StateValue.string(typeId));
        fields.put("codecVersion", StateValue.number(BigDecimal.valueOf(codecVersion)));
        fields.put("value", value);
        return StateValue.object(fields);
    }

    /**
     * Parses the stable JSON-shaped representation.
     *
     * @param state encoded state object
     * @return typed encoded-state descriptor
     * @throws SerializationException when required fields have invalid types
     */
    public static EncodedState fromStateValue(StateValue state) {
        if (!(state instanceof StateValue.ObjectValue object)) {
            throw malformed("Encoded state must be an object.");
        }
        StateValue typeValue = object.require("typeId");
        StateValue versionValue = object.require("codecVersion");
        if (!(typeValue instanceof StateValue.StringValue type)) {
            throw malformed("Encoded state typeId must be a string.");
        }
        if (!(versionValue instanceof StateValue.NumberValue version)
                || version.value().scale() > 0
                || !version.value().equals(BigDecimal.valueOf(version.value().intValue()))) {
            throw malformed("Encoded state codecVersion must be an integer.");
        }
        return new EncodedState(type.value(), version.value().intValueExact(), object.require("value"));
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }
}
