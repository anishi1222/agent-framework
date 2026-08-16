// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;

/**
 * Attributes one request message to its contributing pipeline component.
 *
 * @param sourceType component category
 * @param sourceId optional component identifier
 */
public record AgentRequestMessageSourceAttribution(AgentRequestMessageSourceType sourceType, String sourceId) {
    /** Metadata key used for the JSON-safe attribution object. */
    public static final String METADATA_KEY = "_attribution";

    /** Creates validated source attribution. */
    public AgentRequestMessageSourceAttribution {
        sourceType = AgentValidation.requireNonNull(sourceType, "sourceType");
        if (sourceId != null) {
            sourceId = AgentValidation.requireNonBlank(sourceId, "sourceId");
        }
    }

    StateValue.ObjectValue toStateValue() {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("sourceType", StateValue.string(sourceType.value()));
        if (sourceId != null) {
            values.put("sourceId", StateValue.string(sourceId));
        }
        return StateValue.object(values);
    }

    static AgentRequestMessageSourceAttribution fromStateValue(StateValue value) {
        if (!(value instanceof StateValue.ObjectValue object)
                || !(object.values().get("sourceType") instanceof StateValue.StringValue type)) {
            return null;
        }
        StateValue sourceIdValue = object.values().get("sourceId");
        if (sourceIdValue != null && !(sourceIdValue instanceof StateValue.StringValue)) {
            return null;
        }
        String sourceId = sourceIdValue instanceof StateValue.StringValue string ? string.value() : null;
        if (type.value().isBlank() || sourceId != null && sourceId.isBlank()) {
            return null;
        }
        return new AgentRequestMessageSourceAttribution(new AgentRequestMessageSourceType(type.value()), sourceId);
    }

    @Override
    public String toString() {
        return sourceId == null ? sourceType.toString() : sourceType + ":" + sourceId;
    }
}
