// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.StateValue;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class CodeActTranscript {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CodeActTranscript() {}

    static String render(List<CodeActEvent> events) {
        StringBuilder transcript = new StringBuilder();
        for (CodeActEvent event : events) {
            transcript
                    .append(event.sequence())
                    .append('|')
                    .append(event.eventId())
                    .append('|')
                    .append(event.type())
                    .append('|')
                    .append(event.stepIndex())
                    .append('|')
                    .append(event.stepId() == null ? "-" : event.stepId())
                    .append('|');
            appendValue(transcript, event.data());
            transcript.append('\n');
        }
        return transcript.toString();
    }

    private static void appendValue(StringBuilder target, StateValue value) {
        switch (value) {
            case StateValue.ObjectValue object -> appendObject(target, object);
            case StateValue.ArrayValue array -> appendArray(target, array);
            case StateValue.StringValue string -> appendString(target, string.value());
            case StateValue.NumberValue number -> target.append(number.value().toPlainString());
            case StateValue.BooleanValue bool -> target.append(bool.value());
            case StateValue.NullValue nullValue -> {
                if (nullValue != StateValue.NullValue.INSTANCE) {
                    throw new IllegalStateException("Unsupported null state value.");
                }
                target.append("null");
            }
        }
    }

    private static void appendObject(StringBuilder target, StateValue.ObjectValue object) {
        target.append('{');
        boolean first = true;
        for (Map.Entry<String, StateValue> entry : object.values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList()) {
            if (!first) {
                target.append(',');
            }
            appendString(target, entry.getKey());
            target.append(':');
            appendValue(target, entry.getValue());
            first = false;
        }
        target.append('}');
    }

    private static void appendArray(StringBuilder target, StateValue.ArrayValue array) {
        target.append('[');
        for (int index = 0; index < array.values().size(); index++) {
            if (index > 0) {
                target.append(',');
            }
            appendValue(target, array.values().get(index));
        }
        target.append(']');
    }

    private static void appendString(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (current < 0x20) {
                        target.append("\\u");
                        target.append(HEX[(current >>> 12) & 0xF]);
                        target.append(HEX[(current >>> 8) & 0xF]);
                        target.append(HEX[(current >>> 4) & 0xF]);
                        target.append(HEX[current & 0xF]);
                    } else {
                        target.append(current);
                    }
                }
            }
        }
        target.append('"');
    }
}
