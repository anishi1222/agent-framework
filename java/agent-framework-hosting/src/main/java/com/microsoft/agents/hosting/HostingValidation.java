// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class HostingValidation {
    private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    private HostingValidation() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        return value == null ? null : nonBlank(value, name);
    }

    static String routeId(String value) {
        String checked = nonBlank(value, "routeId");
        if (checked.length() > 64 || !ROUTE_ID.matcher(checked).matches()) {
            throw new ValidationException(
                    "routeId must be at most 64 characters and match [a-z][a-z0-9]*(?:-[a-z0-9]+)*.");
        }
        return checked;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static <T> List<T> copyList(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " contains null");
        }
        return List.copyOf(values);
    }

    static Map<String, String> copyStrings(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(nonBlank(key, name + " key"), nonBlank(value, name + " value")));
        return Map.copyOf(copy);
    }

    static Map<String, List<String>> copyHeaders(Map<String, ? extends List<String>> headers) {
        Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String normalized = nonBlank(name, "header name").toLowerCase(Locale.ROOT);
            if (copy.containsKey(normalized)) {
                throw new ValidationException("headers contain duplicate case-insensitive name '" + normalized + "'.");
            }
            copy.put(normalized, copyList(values, "header values"));
        });
        return Map.copyOf(copy);
    }

    static void rejectUnknown(Set<String> actual, Set<String> allowed, String type) {
        actual.stream()
                .filter(name -> !allowed.contains(name))
                .sorted()
                .findFirst()
                .ifPresent(name -> {
                    throw new HostingException(
                            HostingErrorCode.MALFORMED_REQUEST, type + " contains unknown member '" + name + "'.");
                });
    }
}
