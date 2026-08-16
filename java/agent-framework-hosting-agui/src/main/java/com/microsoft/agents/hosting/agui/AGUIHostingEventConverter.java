// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.protocols.agui.AGUIAgentEventConverter;
import com.microsoft.agents.protocols.agui.AGUIErrorCode;
import com.microsoft.agents.protocols.agui.AGUIEvent;
import com.microsoft.agents.protocols.agui.AGUIEvents;
import com.microsoft.agents.protocols.agui.AGUIJsonCodec;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

final class AGUIHostingEventConverter {
    private final HostingRouteKind kind;

    private final AGUIAgentEventConverter agent;

    AGUIHostingEventConverter(HostingRouteKind kind, String clientRunId, AGUIJsonCodec codec) {
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        agent = new AGUIAgentEventConverter(clientRunId, codec);
    }

    List<AGUIEvent> convert(HostingEvent event) {
        if (event.type() == HostingEventType.AGENT_UPDATE) {
            return agent.accept(decodeAgentUpdate(object(event.data(), "agent update")));
        }
        if (event.type() == HostingEventType.WORKFLOW_EVENT) {
            return workflow(object(event.data(), "workflow event"), event);
        }
        if (event.type() == HostingEventType.ORCHESTRATION_EVENT) {
            return orchestration(object(event.data(), "orchestration event"), event);
        }
        throw invalid("Unsupported generic hosting event for AG-UI conversion.");
    }

    List<AGUIEvent> finish() {
        return kind == HostingRouteKind.AGENT ? agent.finish() : List.of();
    }

    private static List<AGUIEvent> workflow(StateValue.ObjectValue value, HostingEvent source) {
        String event = string(value, "event");
        BigDecimal timestamp = timestamp(source);
        return switch (event) {
            case "superstep-started" ->
                List.of(new AGUIEvents.StepStarted(
                        "workflow-superstep-" + integer(value, "superstep"), timestamp, source.data()));
            case "superstep-completed" ->
                List.of(new AGUIEvents.StepFinished(
                        "workflow-superstep-" + integer(value, "superstep"), timestamp, source.data()));
            case "state-committed" ->
                List.of(new AGUIEvents.Custom(
                        "microsoft.agent-framework/workflow-state-committed",
                        value.values().getOrDefault("data", StateValue.nullValue()),
                        timestamp,
                        source.data()));
            case "checkpoint-saved", "checkpoint-loaded", "workflow-resumed" ->
                List.of(new AGUIEvents.Custom(
                        "microsoft.agent-framework/workflow-" + event,
                        value.values().getOrDefault("data", StateValue.nullValue()),
                        timestamp,
                        source.data()));
            case "run-started", "run-completed" -> List.of();
            default ->
                List.of(new AGUIEvents.Custom(
                        "microsoft.agent-framework/workflow-" + event,
                        value.values().getOrDefault("data", StateValue.nullValue()),
                        timestamp,
                        source.data()));
        };
    }

    private static List<AGUIEvent> orchestration(StateValue.ObjectValue value, HostingEvent source) {
        String event = string(value, "event").toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        BigDecimal timestamp = timestamp(source);
        StateValue data = value.values().getOrDefault("data", StateValue.nullValue());
        if ("plan-updated".equals(event) || "progress-assessed".equals(event)) {
            StateValue.ObjectValue content =
                    data instanceof StateValue.ObjectValue object ? object : StateValue.object(Map.of("value", data));
            return List.of(new AGUIEvents.ActivitySnapshot(
                    string(value, "eventId"),
                    event.toUpperCase(java.util.Locale.ROOT).replace('-', '_'),
                    content,
                    true,
                    timestamp,
                    source.data()));
        }
        if ("run-started".equals(event) || "run-completed".equals(event)) {
            return List.of();
        }
        return List.of(new AGUIEvents.Custom(
                "microsoft.agent-framework/orchestration-" + event, data, timestamp, source.data()));
    }

    private static AgentResponseUpdate decodeAgentUpdate(StateValue.ObjectValue object) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder();
        StateValue sequence = object.values().get("sourceSequence");
        if (sequence instanceof StateValue.NumberValue number) {
            builder.sequence(number.value().longValueExact());
        }
        StateValue contents = object.values().get("contents");
        if (!(contents instanceof StateValue.ArrayValue array)) {
            throw invalid("Generic agent update contents must be an array.");
        }
        ContentStateCodec contentCodec = new ContentStateCodec();
        List<Content> decoded = array.values().stream()
                .map(value -> contentCodec.decode(value, ContentStateCodec.VERSION))
                .toList();
        builder.contents(decoded);
        putRole(builder, object);
        putString(builder::authorName, object, "authorName");
        putString(builder::agentId, object, "agentId");
        putString(builder::responseId, object, "responseId");
        putString(builder::messageId, object, "messageId");
        String createdAt = optionalString(object, "createdAt");
        if (createdAt != null) {
            try {
                builder.createdAt(Instant.parse(createdAt));
            } catch (DateTimeParseException exception) {
                throw invalid("Generic agent update createdAt is malformed.");
            }
        }
        String finishReason = optionalString(object, "finishReason");
        if (finishReason != null) {
            builder.finishReason(FinishReason.of(finishReason));
        }
        StateValue usage = object.values().get("usage");
        if (usage instanceof StateValue.ObjectValue usageObject) {
            builder.usage(new UsageDetails(usageObject.values()));
        }
        StateValue continuation = object.values().get("continuationToken");
        if (continuation != null) {
            builder.continuationToken(continuation);
        }
        StateValue metadata = object.values().get("metadata");
        if (metadata instanceof StateValue.ObjectValue metadataObject) {
            builder.metadata(metadataObject.values());
        }
        return builder.build();
    }

    private static void putRole(AgentResponseUpdate.Builder builder, StateValue.ObjectValue object) {
        String role = optionalString(object, "role");
        if (role != null) {
            builder.role(Role.of(role));
        }
    }

    private static void putString(
            java.util.function.Consumer<String> setter, StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static StateValue.ObjectValue object(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw invalid(name + " must be an object.");
    }

    private static String string(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null || value.isBlank()) {
            throw invalid(name + " must be a non-blank string.");
        }
        return value;
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw invalid(name + " must be a string when present.");
    }

    private static long integer(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.NumberValue number) {
            return number.value().longValueExact();
        }
        throw invalid(name + " must be an integer.");
    }

    private static BigDecimal timestamp(HostingEvent event) {
        return BigDecimal.valueOf(event.createdAt().toEpochMilli());
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_MODEL, message);
    }
}
