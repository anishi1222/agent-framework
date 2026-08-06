// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Carries deterministic audit metadata for one compaction attempt.
 *
 * @param strategy stable strategy name
 * @param changed whether the projected history changed
 * @param originalMessageCount source message count
 * @param compactedMessageCount result message count
 * @param originalGroupCount source atomic-group count
 * @param compactedGroupCount retained atomic-group count, including a generated summary
 * @param originalEstimatedTokens source estimated token count
 * @param compactedEstimatedTokens result estimated token count
 * @param removedMessageIds removed stable or synthetic message identifiers in source order
 * @param summarizedMessageIds message identifiers represented by a generated summary
 * @param summaryMessageId generated summary identifier, or {@code null}
 * @param configuredLimit positive configured limit, or {@code null}
 * @param limitStatus explicit limit result
 */
public record CompactionAudit(
        String strategy,
        boolean changed,
        int originalMessageCount,
        int compactedMessageCount,
        int originalGroupCount,
        int compactedGroupCount,
        long originalEstimatedTokens,
        long compactedEstimatedTokens,
        List<String> removedMessageIds,
        List<String> summarizedMessageIds,
        String summaryMessageId,
        Long configuredLimit,
        CompactionLimitStatus limitStatus) {
    /** Creates and validates immutable audit metadata. */
    public CompactionAudit {
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("strategy must not be blank.");
        }
        if (originalMessageCount < 0
                || compactedMessageCount < 0
                || originalGroupCount < 0
                || compactedGroupCount < 0) {
            throw new IllegalArgumentException("message and group counts must not be negative.");
        }
        if (originalEstimatedTokens < 0 || compactedEstimatedTokens < 0) {
            throw new IllegalArgumentException("token estimates must not be negative.");
        }
        removedMessageIds = List.copyOf(removedMessageIds);
        summarizedMessageIds = List.copyOf(summarizedMessageIds);
        if (summaryMessageId != null && summaryMessageId.isBlank()) {
            throw new IllegalArgumentException("summaryMessageId must not be blank when present.");
        }
        if (configuredLimit != null && configuredLimit <= 0) {
            throw new IllegalArgumentException("configuredLimit must be positive when present.");
        }
        if (limitStatus == null) {
            throw new NullPointerException("limitStatus");
        }
    }

    /**
     * Converts this audit to framework-owned JSON-shaped metadata.
     *
     * @return immutable state object
     */
    public StateValue.ObjectValue toStateValue() {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("strategy", StateValue.string(strategy));
        values.put("changed", StateValue.bool(changed));
        values.put("originalMessageCount", StateValue.integer(originalMessageCount));
        values.put("compactedMessageCount", StateValue.integer(compactedMessageCount));
        values.put("originalGroupCount", StateValue.integer(originalGroupCount));
        values.put("compactedGroupCount", StateValue.integer(compactedGroupCount));
        values.put("originalEstimatedTokens", StateValue.integer(originalEstimatedTokens));
        values.put("compactedEstimatedTokens", StateValue.integer(compactedEstimatedTokens));
        values.put(
                "removedMessageIds",
                StateValue.array(
                        removedMessageIds.stream().map(StateValue::string).toList()));
        values.put(
                "summarizedMessageIds",
                StateValue.array(
                        summarizedMessageIds.stream().map(StateValue::string).toList()));
        values.put(
                "summaryMessageId",
                summaryMessageId == null ? StateValue.nullValue() : StateValue.string(summaryMessageId));
        values.put(
                "configuredLimit",
                configuredLimit == null ? StateValue.nullValue() : StateValue.integer(configuredLimit));
        values.put("limitStatus", StateValue.string(limitStatus.name()));
        return StateValue.object(values);
    }
}
