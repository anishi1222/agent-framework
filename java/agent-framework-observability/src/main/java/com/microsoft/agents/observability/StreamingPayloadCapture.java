// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.Role;
import java.util.List;

final class StreamingPayloadCapture {
    private final TelemetrySanitizer sanitizer;

    private final boolean enabled;

    private final int maximumCharacters;

    private final StringBuilder messages = new StringBuilder();

    private boolean seen;

    private boolean truncated;

    private boolean finished;

    StreamingPayloadCapture(AgentFrameworkTelemetry telemetry, TelemetrySanitizer sanitizer) {
        this.sanitizer = sanitizer;
        enabled = telemetry.contentPolicy().captureContent();
        maximumCharacters = telemetry.contentPolicy().maxStreamingCaptureCharacters();
    }

    synchronized void add(Role role, List<? extends Content> contents) {
        if (!enabled || finished || truncated) {
            return;
        }
        seen = true;
        int separator = messages.isEmpty() ? 0 : 1;
        int contentLimit = maximumCharacters - 2;
        int remaining = contentLimit - messages.length() - separator;
        if (remaining < 0) {
            truncated = true;
            return;
        }
        String sanitized = sanitizer.message(role, contents, remaining);
        if (sanitized == null) {
            truncated = true;
            return;
        }
        if (separator == 1) {
            messages.append(',');
        }
        messages.append(sanitized);
    }

    synchronized void record(TelemetryOperation operation) {
        if (finished) {
            return;
        }
        finished = true;
        if (!enabled || !seen) {
            return;
        }
        operation.stringAttribute(GenAiAttributes.OUTPUT_MESSAGES, value());
        if (truncated) {
            operation.booleanAttribute(GenAiAttributes.OUTPUT_MESSAGES_TRUNCATED, true);
        }
    }

    synchronized String value() {
        return enabled && seen ? "[" + messages + "]" : null;
    }

    synchronized boolean truncated() {
        return truncated;
    }

    synchronized int capturedCharacters() {
        return enabled && seen ? messages.length() + 2 : 0;
    }
}
