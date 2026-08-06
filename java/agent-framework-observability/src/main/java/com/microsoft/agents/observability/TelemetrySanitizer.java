// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

final class TelemetrySanitizer {
    private static final String REDACTED = "[REDACTED]";

    private static final String TRUNCATED = "...[truncated]";

    private final TelemetryContentPolicy policy;

    private final IdentifierPolicy identifierPolicy;

    TelemetrySanitizer(AgentFrameworkTelemetry telemetry) {
        policy = telemetry.contentPolicy();
        identifierPolicy = telemetry.identifierPolicy();
    }

    String identifier(String value) {
        if (value == null || identifierPolicy == IdentifierPolicy.OMIT) {
            return null;
        }
        if (identifierPolicy == IdentifierPolicy.PLAIN) {
            return scalar(value);
        }
        return hashIdentifier(value);
    }

    String messages(List<Message> messages) {
        if (!policy.captureContent()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < messages.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            message(builder, messages.get(index));
        }
        return builder.append(']').toString();
    }

    String message(Message message) {
        if (!policy.captureContent()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        message(builder, message);
        return builder.toString();
    }

    String message(Message message, int maximumCharacters) {
        return message(message.role(), message.contents(), maximumCharacters);
    }

    String message(Role role, List<? extends Content> contents, int maximumCharacters) {
        if (!policy.captureContent()) {
            return null;
        }
        if (maximumCharacters < 0) {
            throw new IllegalArgumentException("maximumCharacters must not be negative.");
        }
        LimitedWriter writer = new LimitedWriter(maximumCharacters);
        try {
            message(writer, role, contents);
            return writer.value();
        } catch (CaptureLimitReachedException ignored) {
            return null;
        }
    }

    String state(StateValue value) {
        if (!policy.captureContent()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        state(builder, value, null);
        return builder.toString();
    }

    String value(Object value) {
        if (!policy.captureContent() || value == null) {
            return null;
        }
        if (value instanceof StateValue stateValue) {
            return state(stateValue);
        }
        if (value instanceof CharSequence text) {
            return scalar(text.toString());
        }
        return null;
    }

    String scalar(String value) {
        return scalar(value, policy.maxValueCharacters());
    }

    private String scalar(String value, int maximum) {
        if (value == null) {
            return null;
        }
        int scannedCodePoints = 0;
        int scannedCharacters = 0;
        while (scannedCharacters < value.length() && scannedCodePoints < maximum) {
            int codePoint = value.codePointAt(scannedCharacters);
            scannedCharacters += Character.charCount(codePoint);
            scannedCodePoints++;
        }
        boolean truncated = scannedCharacters < value.length();
        String marker = maximum >= TRUNCATED.length() ? TRUNCATED : TRUNCATED.substring(0, maximum);
        int contentLimit = truncated ? maximum - marker.length() : maximum;
        StringBuilder builder = new StringBuilder(Math.min(Math.min(maximum, value.length()), 256));
        int characterIndex = 0;
        for (int count = 0; count < contentLimit && characterIndex < value.length(); count++) {
            int codePoint = value.codePointAt(characterIndex);
            characterIndex += Character.charCount(codePoint);
            builder.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint);
        }
        if (truncated) {
            builder.append(marker);
        }
        return builder.toString();
    }

    private String boundedIdentifier(String value, int maximumCharacters) {
        if (value == null || identifierPolicy == IdentifierPolicy.OMIT) {
            return null;
        }
        if (identifierPolicy == IdentifierPolicy.PLAIN) {
            return scalar(value, Math.min(policy.maxValueCharacters(), maximumCharacters));
        }
        return hashIdentifier(value);
    }

    private static String hashIdentifier(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
                writer.write(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to hash telemetry identifier.", exception);
        }
    }

    private void content(StringBuilder builder, Content content) {
        builder.append("{\"type\":");
        string(builder, scalar(content.kind()));
        if (content instanceof TextContent text) {
            builder.append(",\"content\":");
            string(builder, scalar(text.text()));
        } else if (content instanceof ReasoningContent reasoning) {
            builder.append(",\"content\":");
            string(builder, scalar(reasoning.text() == null ? "[protected]" : reasoning.text()));
        } else if (content instanceof FunctionCallContent call) {
            builder.append(",\"id\":");
            string(builder, identifier(call.callId()));
            builder.append(",\"name\":");
            string(builder, scalar(call.name()));
            builder.append(",\"arguments\":");
            state(builder, call.arguments(), null);
        } else if (content instanceof FunctionResultContent result) {
            builder.append(",\"id\":");
            string(builder, identifier(result.callId()));
            builder.append(",\"response\":");
            state(builder, result.result(), null);
        }
        builder.append('}');
    }

    private void message(StringBuilder builder, Message message) {
        builder.append("{\"role\":");
        string(builder, scalar(message.role().value()));
        builder.append(",\"parts\":[");
        for (int contentIndex = 0; contentIndex < message.contents().size(); contentIndex++) {
            if (contentIndex > 0) {
                builder.append(',');
            }
            content(builder, message.contents().get(contentIndex));
        }
        builder.append("]}");
    }

    private void content(LimitedWriter writer, Content content) {
        writer.append("{\"type\":");
        string(writer, scalar(content.kind(), writer.remaining()));
        if (content instanceof TextContent text) {
            writer.append(",\"content\":");
            string(writer, scalar(text.text(), writer.remaining()));
        } else if (content instanceof ReasoningContent reasoning) {
            writer.append(",\"content\":");
            String text = reasoning.text() == null ? "[protected]" : reasoning.text();
            string(writer, scalar(text, writer.remaining()));
        } else if (content instanceof FunctionCallContent call) {
            writer.append(",\"id\":");
            string(writer, boundedIdentifier(call.callId(), writer.remaining()));
            writer.append(",\"name\":");
            string(writer, scalar(call.name(), writer.remaining()));
            writer.append(",\"arguments\":");
            state(writer, call.arguments(), null);
        } else if (content instanceof FunctionResultContent result) {
            writer.append(",\"id\":");
            string(writer, boundedIdentifier(result.callId(), writer.remaining()));
            writer.append(",\"response\":");
            state(writer, result.result(), null);
        }
        writer.append('}');
    }

    private void message(LimitedWriter writer, Role role, List<? extends Content> contents) {
        writer.append("{\"role\":");
        string(writer, scalar(role.value(), writer.remaining()));
        writer.append(",\"parts\":[");
        for (int contentIndex = 0; contentIndex < contents.size(); contentIndex++) {
            if (contentIndex > 0) {
                writer.append(',');
            }
            content(writer, contents.get(contentIndex));
        }
        writer.append("]}");
    }

    private void state(StringBuilder builder, StateValue value, String key) {
        if (key != null && shouldRedact(key)) {
            string(builder, REDACTED);
        } else if (value instanceof StateValue.ObjectValue object) {
            builder.append('{');
            int[] count = {0};
            object.values().keySet().stream().sorted().forEach(member -> {
                if (count[0]++ > 0) {
                    builder.append(',');
                }
                string(builder, scalar(member));
                builder.append(':');
                state(builder, object.values().get(member), member);
            });
            builder.append('}');
        } else if (value instanceof StateValue.ArrayValue array) {
            builder.append('[');
            for (int index = 0; index < array.values().size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                state(builder, array.values().get(index), key);
            }
            builder.append(']');
        } else if (value instanceof StateValue.StringValue string) {
            string(builder, scalar(string.value()));
        } else if (value instanceof StateValue.NumberValue number) {
            builder.append(number.value().toString());
        } else if (value instanceof StateValue.BooleanValue bool) {
            builder.append(bool.value());
        } else {
            builder.append("null");
        }
    }

    private void state(LimitedWriter writer, StateValue value, String key) {
        if (key != null && shouldRedact(key)) {
            string(writer, REDACTED);
        } else if (value instanceof StateValue.ObjectValue object) {
            writer.append('{');
            int[] count = {0};
            object.values().keySet().stream().sorted().forEach(member -> {
                if (count[0]++ > 0) {
                    writer.append(',');
                }
                string(writer, scalar(member, writer.remaining()));
                writer.append(':');
                state(writer, object.values().get(member), member);
            });
            writer.append('}');
        } else if (value instanceof StateValue.ArrayValue array) {
            writer.append('[');
            for (int index = 0; index < array.values().size(); index++) {
                if (index > 0) {
                    writer.append(',');
                }
                state(writer, array.values().get(index), key);
            }
            writer.append(']');
        } else if (value instanceof StateValue.StringValue string) {
            string(writer, scalar(string.value(), writer.remaining()));
        } else if (value instanceof StateValue.NumberValue number) {
            writer.append(number.value().toString());
        } else if (value instanceof StateValue.BooleanValue bool) {
            writer.append(Boolean.toString(bool.value()));
        } else {
            writer.append("null");
        }
    }

    private boolean shouldRedact(String key) {
        String normalized =
                key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(".", "");
        if (policy.redactedKeys().contains(key.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return normalized.contains("authorization")
                || normalized.contains("apikey")
                || normalized.contains("token")
                || normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("cookie")
                || normalized.contains("privatekey");
    }

    private static void string(StringBuilder builder, String value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                builder.append('\\');
            }
            builder.append(character);
        }
        builder.append('"');
    }

    private static void string(LimitedWriter writer, String value) {
        if (value == null) {
            writer.append("null");
            return;
        }
        writer.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                writer.append('\\');
            }
            writer.append(character);
        }
        writer.append('"');
    }

    private static final class LimitedWriter {
        private final StringBuilder builder;

        private final int maximumCharacters;

        private LimitedWriter(int maximumCharacters) {
            builder = new StringBuilder(Math.min(maximumCharacters, 256));
            this.maximumCharacters = maximumCharacters;
        }

        private void append(char value) {
            requireRemaining(1);
            builder.append(value);
        }

        private void append(String value) {
            requireRemaining(value.length());
            builder.append(value);
        }

        private int remaining() {
            return maximumCharacters - builder.length();
        }

        private String value() {
            return builder.toString();
        }

        private void requireRemaining(int characters) {
            if (characters > remaining()) {
                throw CaptureLimitReachedException.INSTANCE;
            }
        }
    }

    private static final class CaptureLimitReachedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private static final CaptureLimitReachedException INSTANCE = new CaptureLimitReachedException();

        private CaptureLimitReachedException() {
            super(null, null, false, false);
        }
    }
}
