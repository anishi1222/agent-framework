// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.RunCancellation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one complete Telegram webhook HTTP request without exposing a server framework type.
 *
 * @param method HTTP method
 * @param headers immutable lower-case header map
 * @param body complete request body
 * @param cancellation disconnect and request cancellation signal
 */
public record TelegramWebhookRequest(
        String method, Map<String, List<String>> headers, byte[] body, RunCancellation cancellation) {
    /** Creates a validated immutable request. */
    public TelegramWebhookRequest {
        method = TelegramValidation.nonBlank(method, "method").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String normalized = TelegramValidation.nonBlank(name, "header name").toLowerCase(Locale.ROOT);
            if (copy.containsKey(normalized)) {
                throw new IllegalArgumentException("headers contain a duplicate normalized name.");
            }
            copy.put(normalized, List.copyOf(Objects.requireNonNull(values, "header values")));
        });
        headers = Map.copyOf(copy);
        body = Objects.requireNonNull(body, "body").clone();
        Objects.requireNonNull(cancellation, "cancellation");
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns every value for a case-insensitive header.
     *
     * @param name header name
     * @return immutable values, or an empty list
     */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(TelegramValidation.nonBlank(name, "name").toLowerCase(Locale.ROOT), List.of());
    }
}
