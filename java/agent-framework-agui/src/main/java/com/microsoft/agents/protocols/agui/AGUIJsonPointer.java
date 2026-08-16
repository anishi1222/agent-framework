// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AGUIJsonPointer {
    private static final int MAX_SEGMENTS = 256;

    private static final Set<String> BLOCKED_SEGMENTS = Set.of("__proto__", "prototype", "constructor");

    private AGUIJsonPointer() {}

    static List<String> parse(String pointer) {
        java.util.Objects.requireNonNull(pointer, "pointer");
        if (pointer.isEmpty()) {
            return List.of();
        }
        if (pointer.charAt(0) != '/') {
            throw invalid("JSON Pointer must be empty or begin with '/'.");
        }
        String[] encoded = pointer.substring(1).split("/", -1);
        if (encoded.length > MAX_SEGMENTS) {
            throw invalid("JSON Pointer exceeds the segment limit.");
        }
        ArrayList<String> segments = new ArrayList<>(encoded.length);
        for (String segment : encoded) {
            String decoded = decode(segment);
            if (decoded.indexOf('\0') >= 0 || BLOCKED_SEGMENTS.contains(decoded)) {
                throw invalid("JSON Pointer contains a blocked path segment.");
            }
            segments.add(decoded);
        }
        return List.copyOf(segments);
    }

    private static String decode(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '~') {
                decoded.append(character);
                continue;
            }
            if (++index >= value.length()) {
                throw invalid("JSON Pointer contains an invalid escape.");
            }
            char escaped = value.charAt(index);
            if (escaped == '0') {
                decoded.append('~');
            } else if (escaped == '1') {
                decoded.append('/');
            } else {
                throw invalid("JSON Pointer contains an invalid escape.");
            }
        }
        return decoded.toString();
    }

    static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_PATCH, message);
    }
}
