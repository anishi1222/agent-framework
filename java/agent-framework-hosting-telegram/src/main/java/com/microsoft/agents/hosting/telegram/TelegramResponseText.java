// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import java.util.Objects;

final class TelegramResponseText {
    static final String NO_RESPONSE_TEXT = "(no response)";

    private TelegramResponseText() {}

    static String finite(HostingOutcome outcome, int maximumLength) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.status() != HostingOutcomeStatus.COMPLETED) {
            throw new TelegramDispatchException(outcome.error());
        }
        BoundedText accumulator = new BoundedText(maximumLength);
        if (outcome.result() instanceof StateValue.ObjectValue result
                && result.values().get("messages") instanceof StateValue.ArrayValue messages) {
            for (StateValue messageValue : messages.values()) {
                appendMessage(accumulator, messageValue);
            }
        }
        return accumulator.finish();
    }

    static void appendStreaming(BoundedText accumulator, HostingEvent event) {
        Objects.requireNonNull(accumulator, "accumulator");
        Objects.requireNonNull(event, "event");
        if (event.type() != HostingEventType.AGENT_UPDATE) {
            return;
        }
        if (!(event.data() instanceof StateValue.ObjectValue update)) {
            throw new TelegramDispatchException(null);
        }
        StateValue contentsValue = update.values().get("contents");
        if (!(contentsValue instanceof StateValue.ArrayValue contents)) {
            throw new TelegramDispatchException(null);
        }
        for (StateValue contentValue : contents.values()) {
            appendContent(accumulator, contentValue);
        }
    }

    private static void appendMessage(BoundedText accumulator, StateValue value) {
        if (!(value instanceof StateValue.ObjectValue message)) {
            throw new TelegramDispatchException(null);
        }
        StateValue contentsValue = message.values().get("contents");
        if (!(contentsValue instanceof StateValue.ArrayValue contents)) {
            throw new TelegramDispatchException(null);
        }
        for (StateValue contentValue : contents.values()) {
            appendContent(accumulator, contentValue);
        }
    }

    private static void appendContent(BoundedText accumulator, StateValue value) {
        if (!(value instanceof StateValue.ObjectValue content)) {
            throw new TelegramDispatchException(null);
        }
        StateValue kindValue = content.values().get("kind");
        if (!(kindValue instanceof StateValue.StringValue kind) || !"text".equals(kind.value())) {
            return;
        }
        StateValue textValue = content.values().get("text");
        if (!(textValue instanceof StateValue.StringValue text)) {
            throw new TelegramDispatchException(null);
        }
        accumulator.append(text.value());
    }

    static final class BoundedText {
        private final int maximumLength;

        private final StringBuilder value;

        BoundedText(int maximumLength) {
            this.maximumLength = TelegramValidation.positive(maximumLength, "maximumLength");
            value = new StringBuilder(Math.min(maximumLength, 256));
        }

        void append(String text) {
            Objects.requireNonNull(text, "text");
            int index = repairPendingSurrogate(text);
            while (index < text.length() && value.length() < maximumLength) {
                char current = text.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (index + 1 >= text.length()) {
                        value.append(current);
                        return;
                    }
                    char next = text.charAt(index + 1);
                    if (!Character.isLowSurrogate(next)) {
                        index++;
                        continue;
                    }
                    if (maximumLength - value.length() < 2) {
                        return;
                    }
                    value.append(current).append(next);
                    index += 2;
                    continue;
                }
                if (Character.isLowSurrogate(current)) {
                    index++;
                    continue;
                }
                value.append(current);
                index++;
            }
        }

        String finish() {
            if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
                value.setLength(value.length() - 1);
            }
            String result = value.toString();
            return result.isBlank()
                    ? NO_RESPONSE_TEXT.substring(0, Math.min(maximumLength, NO_RESPONSE_TEXT.length()))
                    : result;
        }

        private int repairPendingSurrogate(String text) {
            if (value.isEmpty() || !Character.isHighSurrogate(value.charAt(value.length() - 1))) {
                return 0;
            }
            if (!text.isEmpty() && Character.isLowSurrogate(text.charAt(0)) && value.length() < maximumLength) {
                value.append(text.charAt(0));
                return 1;
            }
            if (!text.isEmpty()) {
                value.setLength(value.length() - 1);
            }
            return !text.isEmpty() && Character.isLowSurrogate(text.charAt(0)) ? 1 : 0;
        }
    }
}
