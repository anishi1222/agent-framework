// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageContent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class CompactionText {
    private CompactionText() {}

    static String message(Message message) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        StringBuilder builder = new StringBuilder();
        append(builder, message.role().value());
        append(builder, message.authorName());
        append(builder, message.messageId());
        for (Content content : message.contents()) {
            content(builder, content);
        }
        stateMap(builder, message.metadata());
        return builder.toString();
    }

    static String transcript(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            builder.append(index + 1)
                    .append(". [")
                    .append(message.role().value())
                    .append("] ");
            String text = message.text();
            if (!text.isEmpty()) {
                builder.append(text);
            }
            boolean structured = !message.metadata().isEmpty()
                    || message.contents().stream().anyMatch(content -> !(content instanceof TextContent));
            if (text.isEmpty() || structured) {
                if (!text.isEmpty()) {
                    builder.append("\n   [structured] ");
                }
                builder.append(message(message));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    static String identifier(Message message, int index) {
        return message.messageId() == null ? "index:" + index : message.messageId();
    }

    static String summaryId(List<Message> messages, String summary) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Message message : messages) {
                digest.update(CompactionText.message(message).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            digest.update(summary.getBytes(StandardCharsets.UTF_8));
            return "summary-" + HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static void content(StringBuilder builder, Content content) {
        append(builder, content.kind());
        if (content instanceof TextContent text) {
            append(builder, text.text());
        } else if (content instanceof ReasoningContent reasoning) {
            append(builder, reasoning.id());
            append(builder, reasoning.text());
            append(builder, reasoning.protectedData());
        } else if (content instanceof DataContent data) {
            append(builder, data.mediaType());
            builder.append(data.data().length).append(';');
        } else if (content instanceof UriContent uri) {
            append(builder, uri.uri().toASCIIString());
            append(builder, uri.mediaType());
        } else if (content instanceof FunctionCallContent call) {
            append(builder, call.callId());
            append(builder, call.name());
            state(builder, call.arguments());
            builder.append(call.informationalOnly()).append(';');
        } else if (content instanceof FunctionResultContent result) {
            append(builder, result.callId());
            state(builder, result.result());
            append(builder, result.error());
            for (Content item : result.items()) {
                content(builder, item);
            }
        } else if (content instanceof ErrorContent error) {
            append(builder, error.message());
            append(builder, error.errorCode());
            append(builder, error.details());
        } else if (content instanceof MetadataContent metadata) {
            stateMap(builder, metadata.values());
        } else if (content instanceof UsageContent usage) {
            stateMap(builder, usage.usage().values());
        }
        stateMap(builder, content.metadata());
    }

    private static void state(StringBuilder builder, StateValue value) {
        if (value instanceof StateValue.ObjectValue object) {
            builder.append('{');
            stateMap(builder, object.values());
            builder.append('}');
        } else if (value instanceof StateValue.ArrayValue array) {
            builder.append('[');
            for (StateValue item : array.values()) {
                state(builder, item);
            }
            builder.append(']');
        } else if (value instanceof StateValue.StringValue string) {
            append(builder, string.value());
        } else if (value instanceof StateValue.NumberValue number) {
            append(builder, number.value().toString());
        } else if (value instanceof StateValue.BooleanValue bool) {
            builder.append(bool.value()).append(';');
        } else {
            builder.append("null;");
        }
    }

    private static void stateMap(StringBuilder builder, Map<String, StateValue> values) {
        values.keySet().stream().sorted().forEach(key -> {
            append(builder, key);
            state(builder, values.get(key));
        });
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null) {
            builder.append("-1:");
            return;
        }
        builder.append(value.length()).append(':').append(value).append(';');
    }
}
