// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CodeActEventLog {
    private final String runId;
    private final List<CodeActEventListener> listeners;
    private final ArrayList<CodeActEvent> events = new ArrayList<>();
    private long sequence;

    CodeActEventLog(String runId, List<CodeActEventListener> listeners) {
        this.runId = CodeActValidation.requireNonBlank(runId, "runId");
        this.listeners = CodeActValidation.copyList(listeners, "listeners");
    }

    synchronized CodeActEvent emit(CodeActEventType type, Map<String, StateValue> data) {
        return emit(type, -1, null, data);
    }

    synchronized CodeActEvent emit(
            CodeActEventType type, int stepIndex, CodeActStep step, Map<String, StateValue> data) {
        long current = sequence++;
        CodeActEvent event = new CodeActEvent(
                current,
                runId + ":event:" + current,
                type,
                runId,
                stepIndex,
                step == null ? null : step.id(),
                StateValue.object(new LinkedHashMap<>(data)));
        events.add(event);
        for (CodeActEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // Optional instrumentation cannot alter CodeAct behavior.
            }
        }
        return event;
    }

    synchronized List<CodeActEvent> snapshot() {
        return List.copyOf(events);
    }
}
