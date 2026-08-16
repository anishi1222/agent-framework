// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Replaces older complete tool groups with bounded assistant summaries of their results.
 *
 * <p>The newest configured tool groups remain verbatim. Unresolved, approval-protected, and
 * preamble groups are retained unchanged. Generated summaries carry deterministic forward links to
 * the source messages and groups, so repeated compaction is idempotent.
 */
public final class ToolResultCompactionStrategy implements CompactionStrategy {
    /** Maximum generated summary length. */
    public static final int SUMMARY_MAX_CHARS = 4096;

    private static final String SUMMARY_PREFIX = "[Tool results: ";

    private static final String SUMMARY_SUFFIX = "]";

    private static final String SUMMARY_TRUNCATION_MARKER = "... [truncated]";

    private final int keepLastToolCallGroups;

    /**
     * Creates a tool-result compaction strategy.
     *
     * @param keepLastToolCallGroups non-negative newest tool groups retained verbatim
     */
    public ToolResultCompactionStrategy(int keepLastToolCallGroups) {
        this.keepLastToolCallGroups =
                CompactionSupport.requireNonNegative(keepLastToolCallGroups, "keepLastToolCallGroups");
    }

    /** Creates a strategy retaining the newest tool group verbatim. */
    public ToolResultCompactionStrategy() {
        this(1);
    }

    /** Returns the number of newest tool groups retained verbatim. */
    public int keepLastToolCallGroups() {
        return keepLastToolCallGroups;
    }

    @Override
    public CompletionStage<CompactionResult> compactAsync(CompactionRequest request) {
        CompletionStage<CompactionResult> cancelled = CompactionSupport.cancelledIfRequested(request);
        if (cancelled != null) {
            return cancelled;
        }
        List<CompactionMessageGroup> groups = CompactionSupport.groups(request);
        List<CompactionMessageGroup> toolGroups = groups.stream()
                .filter(group -> group.kind() == CompactionGroupKind.TOOL)
                .toList();
        if (toolGroups.size() <= keepLastToolCallGroups) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, null));
        }

        Set<String> retainedToolIds = new HashSet<>();
        int firstRetained = Math.max(0, toolGroups.size() - keepLastToolCallGroups);
        for (int index = firstRetained; index < toolGroups.size(); index++) {
            retainedToolIds.add(toolGroups.get(index).id());
        }

        BitSet retained = CompactionSupport.allIndexes(request.messages().size());
        LinkedHashMap<Integer, List<Message>> insertions = new LinkedHashMap<>();
        ArrayList<String> summarizedIds = new ArrayList<>();
        for (CompactionMessageGroup group : toolGroups) {
            if (retainedToolIds.contains(group.id()) || group.structurallyProtected()) {
                continue;
            }
            String summaryText = summaryText(group);
            ArrayList<String> groupMessageIds = new ArrayList<>();
            for (int index : group.messageIndexes()) {
                groupMessageIds.add(CompactionText.identifier(request.messages().get(index), index));
                retained.clear(index);
            }
            summarizedIds.addAll(groupMessageIds);
            LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
            metadata.put(
                    SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY,
                    StateValue.array(
                            groupMessageIds.stream().map(StateValue::string).toList()));
            metadata.put(
                    SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY,
                    StateValue.array(List.of(StateValue.string(group.id()))));
            String summaryId = CompactionText.summaryId(group.messages(), group.id() + "\u0000" + summaryText);
            Message summary = Message.builder(Role.ASSISTANT)
                    .contents(List.of(new TextContent(summaryText)))
                    .messageId(summaryId)
                    .metadata(metadata)
                    .build();
            insertions.put(group.messageIndexes().getFirst(), List.of(summary));
        }
        if (insertions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, null));
        }
        return CompletableFuture.completedFuture(CompactionSupport.resultWithInsertions(
                getClass().getSimpleName(),
                request,
                groups,
                retained,
                insertions,
                summarizedIds,
                null,
                CompactionLimitStatus.NOT_APPLICABLE));
    }

    private static String summaryText(CompactionMessageGroup group) {
        LinkedHashMap<String, String> callNames = new LinkedHashMap<>();
        for (Message message : group.messages()) {
            for (Content content : message.contents()) {
                if (content instanceof FunctionCallContent call) {
                    callNames.put(call.callId(), call.name());
                }
            }
        }

        int bodyLimit = SUMMARY_MAX_CHARS - SUMMARY_PREFIX.length() - SUMMARY_SUFFIX.length();
        StringBuilder body = new StringBuilder(Math.min(bodyLimit, 256));
        boolean hasResult = false;
        boolean complete = true;
        outer:
        for (Message message : group.messages()) {
            for (Content content : message.contents()) {
                if (!(content instanceof FunctionResultContent result)) {
                    continue;
                }
                if (hasResult && !append(body, "; ", bodyLimit)) {
                    complete = false;
                    break outer;
                }
                String name = callNames.get(result.callId());
                if (name != null && !append(body, name + ": ", bodyLimit)) {
                    complete = false;
                    break outer;
                }
                if (!appendResult(body, result, bodyLimit)) {
                    complete = false;
                    break outer;
                }
                hasResult = true;
            }
        }
        if (!hasResult) {
            complete = append(body, "no results", bodyLimit);
        }
        if (!complete) {
            int markerStart = Math.max(0, bodyLimit - SUMMARY_TRUNCATION_MARKER.length());
            if (body.length() > markerStart) {
                body.setLength(markerStart);
            }
            while (!body.isEmpty() && Character.isWhitespace(body.charAt(body.length() - 1))) {
                body.setLength(body.length() - 1);
            }
            body.append(SUMMARY_TRUNCATION_MARKER);
        }
        return SUMMARY_PREFIX + body + SUMMARY_SUFFIX;
    }

    private static boolean appendResult(StringBuilder target, FunctionResultContent result, int limit) {
        if (result.error() != null) {
            return append(target, "error: " + result.error(), limit);
        }
        if (!(result.result() instanceof StateValue.NullValue)) {
            return appendState(target, result.result(), limit);
        }
        if (result.items().isEmpty()) {
            return append(target, "null", limit);
        }
        boolean first = true;
        for (Content item : result.items()) {
            if (!first && !append(target, "\n", limit)) {
                return false;
            }
            String value = item instanceof TextContent text ? text.text() : "[" + item.kind() + "]";
            if (!append(target, value, limit)) {
                return false;
            }
            first = false;
        }
        return true;
    }

    private static boolean appendState(StringBuilder target, StateValue value, int limit) {
        if (value instanceof StateValue.StringValue string) {
            return append(target, string.value(), limit);
        }
        if (value instanceof StateValue.NumberValue number) {
            return append(target, number.value().toString(), limit);
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return append(target, Boolean.toString(bool.value()), limit);
        }
        if (value instanceof StateValue.NullValue) {
            return append(target, "null", limit);
        }
        if (value instanceof StateValue.ArrayValue array) {
            if (!append(target, "[", limit)) {
                return false;
            }
            for (int index = 0; index < array.values().size(); index++) {
                if (index > 0 && !append(target, ",", limit)) {
                    return false;
                }
                if (!appendState(target, array.values().get(index), limit)) {
                    return false;
                }
            }
            return append(target, "]", limit);
        }
        StateValue.ObjectValue object = (StateValue.ObjectValue) value;
        if (!append(target, "{", limit)) {
            return false;
        }
        List<Map.Entry<String, StateValue>> entries = object.values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0 && !append(target, ",", limit)) {
                return false;
            }
            Map.Entry<String, StateValue> entry = entries.get(index);
            if (!append(target, entry.getKey() + ":", limit) || !appendState(target, entry.getValue(), limit)) {
                return false;
            }
        }
        return append(target, "}", limit);
    }

    private static boolean append(StringBuilder target, String value, int limit) {
        int remaining = limit - target.length();
        if (remaining <= 0) {
            return false;
        }
        if (value.length() <= remaining) {
            target.append(value);
            return true;
        }
        target.append(value, 0, remaining);
        return false;
    }
}
