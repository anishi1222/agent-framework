// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/** Redacts common credential-bearing members before values cross a hosting boundary. */
public final class HostingRedactor {
    private static final StateValue REDACTED = StateValue.string("[REDACTED]");

    private static final Set<String> SECRET_NAMES = Set.of(
            "apikey",
            "api-key",
            "authorization",
            "cookie",
            "credential",
            "credentials",
            "password",
            "privatekey",
            "private-key",
            "protecteddata",
            "secret",
            "token");

    private HostingRedactor() {}

    /**
     * Recursively redacts values whose object key identifies likely credentials.
     *
     * @param value immutable JSON-shaped value
     * @return redacted immutable value
     */
    public static StateValue redact(StateValue value) {
        return switch (value) {
            case StateValue.ObjectValue object -> redactObject(object);
            case StateValue.ArrayValue array ->
                StateValue.array(
                        array.values().stream().map(HostingRedactor::redact).toList());
            default -> value;
        };
    }

    private static StateValue.ObjectValue redactObject(StateValue.ObjectValue object) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        object.values().forEach((key, value) -> values.put(key, isSecretName(key) ? REDACTED : redact(value)));
        return StateValue.object(values);
    }

    private static boolean isSecretName(String key) {
        String normalized =
                key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
        if (SECRET_NAMES.contains(normalized)) {
            return true;
        }
        return SECRET_NAMES.stream().anyMatch(secret -> normalized.endsWith(secret.replace("-", "")));
    }
}
