// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateEnvelope;
import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;

final class ToolJson {
    private static final JsonStateSerializer SERIALIZER = new JsonStateSerializer(SerializationLimits.defaults());

    private static final String PREFIX = "{\"format\":\""
            + StateEnvelope.FORMAT
            + "\",\"documentKind\":\""
            + DocumentKind.AGENT_SESSION.value()
            + "\",\"payloadVersion\":1,\"payload\":";

    private static final String SUFFIX = "}";

    private ToolJson() {}

    static StateValue.ObjectValue parseObject(String json) {
        ToolValidation.requireNonBlank(json, "function arguments JSON");
        String wrapped = PREFIX + json + SUFFIX;
        try {
            StateEnvelope envelope =
                    SERIALIZER.read(wrapped.getBytes(StandardCharsets.UTF_8), DocumentKind.AGENT_SESSION);
            if (envelope.payload() instanceof StateValue.ObjectValue object) {
                return object;
            }
            throw new ToolBindingException("Function arguments JSON must contain one object.");
        } catch (SerializationException failure) {
            throw new ToolBindingException("Function arguments JSON is invalid.", failure);
        }
    }
}
