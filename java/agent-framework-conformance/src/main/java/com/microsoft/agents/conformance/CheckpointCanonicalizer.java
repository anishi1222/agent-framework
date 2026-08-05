// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Canonicalizes the ordering rules of Java workflow-checkpoint envelope version 1. */
final class CheckpointCanonicalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Comparator<JsonNode> BUFFERED_INPUT_ORDER = Comparator.comparing(
                    (JsonNode input) -> input.get("targetId").textValue())
            .thenComparing(input -> input.get("sourceId").textValue());

    private CheckpointCanonicalizer() {}

    static String encode(JsonNode envelope) throws JsonProcessingException {
        return MAPPER.writeValueAsString(canonicalEnvelope(envelope));
    }

    static ObjectNode canonicalEnvelope(JsonNode envelope) {
        ObjectNode semanticOrder = envelope.deepCopy();
        ObjectNode payload = (ObjectNode) semanticOrder.get("payload");
        payload.set("bufferedInputs", canonicalBufferedInputs(payload.get("bufferedInputs")));
        payload.set("pendingExecutors", canonicalPendingExecutors(payload.get("pendingExecutors")));
        return (ObjectNode) canonicalValue(semanticOrder);
    }

    private static ArrayNode canonicalBufferedInputs(JsonNode bufferedInputs) {
        List<JsonNode> sorted = orderedBufferedInputs(bufferedInputs);
        ArrayNode canonical = NODES.arrayNode();
        for (JsonNode input : sorted) {
            canonical.add(canonicalValue(input));
        }
        return canonical;
    }

    static List<JsonNode> orderedBufferedInputs(JsonNode bufferedInputs) {
        ArrayList<JsonNode> sorted = new ArrayList<>();
        bufferedInputs.forEach(sorted::add);
        sorted.sort(BUFFERED_INPUT_ORDER);
        return List.copyOf(sorted);
    }

    private static ArrayNode canonicalPendingExecutors(JsonNode pendingExecutors) {
        List<String> sorted = new ArrayList<>();
        pendingExecutors.forEach(executor -> sorted.add(executor.textValue()));
        sorted.sort(String::compareTo);
        ArrayNode canonical = NODES.arrayNode();
        sorted.forEach(canonical::add);
        return canonical;
    }

    private static JsonNode canonicalValue(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = NODES.objectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, member) -> canonical.set(key, canonicalValue(member)));
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = NODES.arrayNode();
            value.forEach(element -> canonical.add(canonicalValue(element)));
            return canonical;
        }
        return value.deepCopy();
    }
}
