// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.orchestrations.OrchestrationEvent;
import com.microsoft.agents.orchestrations.OrchestrationResult;
import com.microsoft.agents.tools.ToolApprovalRequest;
import com.microsoft.agents.workflows.WorkflowEvent;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HostingWireValues {
    private static final ContentStateCodec CONTENT_CODEC = new ContentStateCodec();

    private static final Set<String> RUN_REQUEST_FIELDS = Set.of("version", "messages", "input", "options", "metadata");

    private static final Set<String> RESUME_REQUEST_FIELDS = Set.of("version", "token", "type", "decisions", "input");

    private static final Set<String> MESSAGE_FIELDS = Set.of("role", "contents", "authorName", "messageId", "metadata");

    private static final Set<String> OPTIONS_FIELDS = Set.of("maxIterations", "maxFunctionCalls", "metadata");

    private static final Set<String> DECISION_FIELDS = Set.of("approvalId", "approved", "reason");

    private HostingWireValues() {}

    static HostingRunRequest decodeRunRequest(StateValue.ObjectValue object, String wireVersion) {
        HostingValidation.rejectUnknown(object.values().keySet(), RUN_REQUEST_FIELDS, "Hosted run request");
        requireVersion(object, wireVersion);
        List<Message> messages = optionalArray(object, "messages").stream()
                .map(value -> decodeMessage(requireObject(value, "message")))
                .toList();
        StateValue input = optionalValue(object, "input");
        RunOptions options = decodeOptions(optionalObject(object, "options"));
        Map<String, StateValue> metadata = optionalObject(object, "metadata") == null
                ? Map.of()
                : optionalObject(object, "metadata").values();
        try {
            return new HostingRunRequest(messages, input, options, metadata);
        } catch (RuntimeException exception) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Hosted run request is semantically invalid.", exception);
        }
    }

    static HostingResumeRequest decodeResumeRequest(StateValue.ObjectValue object, String wireVersion) {
        HostingValidation.rejectUnknown(object.values().keySet(), RESUME_REQUEST_FIELDS, "Hosted resume request");
        requireVersion(object, wireVersion);
        String token = requireString(object, "token");
        HostingContinuationType type = HostingContinuationType.fromValue(requireString(object, "type"));
        List<HostingApprovalDecision> decisions = optionalArray(object, "decisions").stream()
                .map(value -> decodeDecision(requireObject(value, "decision")))
                .toList();
        try {
            return new HostingResumeRequest(token, type, decisions, optionalValue(object, "input"));
        } catch (RuntimeException exception) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Hosted resume request is semantically invalid.", exception);
        }
    }

    static StateValue.ObjectValue descriptorValue(HostingRouteDescriptor descriptor, String wireVersion) {
        LinkedHashMap<String, StateValue> value = base(wireVersion, "route");
        value.put("id", StateValue.string(descriptor.id()));
        value.put("kind", StateValue.string(descriptor.kind().name().toLowerCase(java.util.Locale.ROOT)));
        putString(value, "name", descriptor.name());
        putString(value, "description", descriptor.description());
        value.put("streamingSupported", StateValue.bool(descriptor.streamingSupported()));
        value.put("resumeSupported", StateValue.bool(descriptor.resumeSupported()));
        value.put("metadata", StateValue.object(descriptor.metadata()));
        return StateValue.object(value);
    }

    static StateValue.ObjectValue descriptorsValue(
            HostingRouteKind kind, List<HostingRouteDescriptor> descriptors, String wireVersion) {
        LinkedHashMap<String, StateValue> value = base(wireVersion, "route-list");
        value.put("kind", StateValue.string(kind.name().toLowerCase(java.util.Locale.ROOT)));
        value.put(
                "items",
                StateValue.array(descriptors.stream()
                        .map(item -> descriptorValue(item, wireVersion))
                        .toList()));
        return StateValue.object(value);
    }

    static StateValue.ObjectValue outcomeValue(HostingOutcome outcome, String wireVersion) {
        LinkedHashMap<String, StateValue> value = base(wireVersion, "outcome");
        value.put("status", StateValue.string(outcome.status().value()));
        value.put("runId", StateValue.string(outcome.runId()));
        if (outcome.result() != null) {
            value.put("result", HostingRedactor.redact(outcome.result()));
        }
        if (outcome.continuation() != null) {
            value.put("continuation", continuationValue(outcome.continuation()));
        }
        if (outcome.error() != null) {
            value.put("error", errorBody(outcome.error()));
        }
        return StateValue.object(value);
    }

    static StateValue.ObjectValue eventValue(HostingEvent event, String wireVersion) {
        LinkedHashMap<String, StateValue> value = base(wireVersion, "event");
        value.put("sequence", StateValue.integer(event.sequence()));
        value.put("event", StateValue.string(event.type().value()));
        value.put("runId", StateValue.string(event.runId()));
        value.put("createdAt", StateValue.string(event.createdAt().toString()));
        value.put("data", HostingRedactor.redact(event.data()));
        return StateValue.object(value);
    }

    static StateValue.ObjectValue errorValue(HostingError error, String wireVersion) {
        LinkedHashMap<String, StateValue> value = base(wireVersion, "error");
        value.put("error", errorBody(error));
        return StateValue.object(value);
    }

    static StateValue agentResponseValue(AgentResponse<?> response) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put(
                "messages",
                StateValue.array(response.messages().stream()
                        .map(HostingWireValues::messageValue)
                        .toList()));
        putString(value, "responseId", response.responseId());
        putString(value, "agentId", response.agentId());
        if (response.createdAt() != null) {
            value.put("createdAt", StateValue.string(response.createdAt().toString()));
        }
        if (response.finishReason() != null) {
            value.put("finishReason", StateValue.string(response.finishReason().value()));
        }
        if (response.usage() != null) {
            value.put("usage", StateValue.object(response.usage().values()));
        }
        if (response.continuationToken() != null) {
            value.put("continuationToken", response.continuationToken());
        }
        value.put("metadata", StateValue.object(response.metadata()));
        value.put(
                "updateSequences",
                StateValue.array(response.updateSequences().stream()
                        .map(StateValue::integer)
                        .toList()));
        if (response.value() != null) {
            value.put("value", simpleValue(response.value()));
        }
        return HostingRedactor.redact(StateValue.object(value));
    }

    static StateValue agentUpdateValue(AgentResponseUpdate update) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        if (update.sequence() != null) {
            value.put("sourceSequence", StateValue.integer(update.sequence()));
        }
        value.put(
                "contents",
                StateValue.array(update.contents().stream()
                        .map(HostingWireValues::contentValue)
                        .toList()));
        if (update.role() != null) {
            value.put("role", StateValue.string(update.role().value()));
        }
        putString(value, "authorName", update.authorName());
        putString(value, "agentId", update.agentId());
        putString(value, "responseId", update.responseId());
        putString(value, "messageId", update.messageId());
        if (update.createdAt() != null) {
            value.put("createdAt", StateValue.string(update.createdAt().toString()));
        }
        if (update.finishReason() != null) {
            value.put("finishReason", StateValue.string(update.finishReason().value()));
        }
        if (update.usage() != null) {
            value.put("usage", StateValue.object(update.usage().values()));
        }
        if (update.continuationToken() != null) {
            value.put("continuationToken", update.continuationToken());
        }
        value.put("metadata", StateValue.object(update.metadata()));
        return HostingRedactor.redact(StateValue.object(value));
    }

    static StateValue workflowEventValue(WorkflowEvent event) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("sourceSequence", StateValue.integer(event.sequence()));
        value.put("event", StateValue.string(event.type().value()));
        value.put("workflowRunId", StateValue.string(event.runId()));
        if (event.nodeId() != null) {
            value.put("nodeId", StateValue.string(event.nodeId().value()));
        }
        value.put("superstep", StateValue.integer(event.superstep()));
        putString(value, "correlationId", event.correlationId());
        value.put("data", event.data());
        return HostingRedactor.redact(StateValue.object(value));
    }

    static StateValue workflowResultValue(WorkflowRunResult<?> result, StateValue encodedOutput) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("workflowRunId", StateValue.string(result.runId()));
        value.put("output", HostingRedactor.redact(encodedOutput));
        value.put("supersteps", StateValue.integer(result.supersteps()));
        if (result.checkpointRevision() != null) {
            value.put("checkpointRevision", StateValue.integer(result.checkpointRevision()));
        }
        return StateValue.object(value);
    }

    static StateValue orchestrationEventValue(OrchestrationEvent event) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("sourceSequence", StateValue.integer(event.sequence()));
        value.put("eventId", StateValue.string(event.eventId()));
        value.put("event", StateValue.string(event.type().name()));
        value.put("orchestrationId", StateValue.string(event.orchestrationId()));
        value.put("orchestrationRunId", StateValue.string(event.runId()));
        putString(value, "participantId", event.participantId());
        value.put("turn", StateValue.integer(event.turn()));
        putString(value, "correlationId", event.correlationId());
        value.put("data", HostingRedactor.redact(event.data()));
        return StateValue.object(value);
    }

    static StateValue orchestrationResultValue(OrchestrationResult<?> result, StateValue encodedOutput) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("orchestrationRunId", StateValue.string(result.runId()));
        value.put("outcome", StateValue.string(result.outcome().name()));
        value.put(
                "terminationReason",
                StateValue.string(result.terminationReason().name()));
        value.put("output", HostingRedactor.redact(encodedOutput));
        value.put("turns", StateValue.integer(result.turns()));
        value.put(
                "transcript",
                StateValue.array(result.transcript().stream()
                        .map(HostingWireValues::messageValue)
                        .toList()));
        value.put(
                "errors",
                StateValue.array(result.errors().stream()
                        .map(error -> StateValue.object(Map.of(
                                "errorType",
                                StateValue.string(error.errorType()),
                                "message",
                                StateValue.string(error.message()))))
                        .toList()));
        return StateValue.object(value);
    }

    static List<HostingApprovalRequest> approvalRequests(List<ToolApprovalRequest> requests) {
        return requests.stream()
                .map(request -> new HostingApprovalRequest(
                        request.approvalId().value(),
                        request.callId(),
                        request.toolName(),
                        requireObject(HostingRedactor.redact(request.arguments()), "redacted arguments")))
                .toList();
    }

    static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, name + " must be an object.");
    }

    static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be a non-blank string.");
    }

    static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be a non-blank string when present.");
    }

    static long requirePositiveLong(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.NumberValue number
                && number.value().scale() <= 0
                && number.value().signum() > 0) {
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException ignored) {
                // Fall through to the stable error.
            }
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be a positive integer.");
    }

    private static HostingApprovalDecision decodeDecision(StateValue.ObjectValue object) {
        HostingValidation.rejectUnknown(object.values().keySet(), DECISION_FIELDS, "Approval decision");
        StateValue approvedValue = object.values().get("approved");
        if (!(approvedValue instanceof StateValue.BooleanValue approved)) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Decision approved member must be Boolean.");
        }
        return new HostingApprovalDecision(
                requireString(object, "approvalId"), approved.value(), optionalString(object, "reason"));
    }

    private static Message decodeMessage(StateValue.ObjectValue object) {
        HostingValidation.rejectUnknown(object.values().keySet(), MESSAGE_FIELDS, "Message");
        List<Content> contents = requireArray(object, "contents").stream()
                .map(value -> decodeContent(requireObject(value, "content")))
                .toList();
        try {
            return new Message(
                    Role.of(requireString(object, "role")),
                    contents,
                    optionalString(object, "authorName"),
                    optionalString(object, "messageId"),
                    optionalObject(object, "metadata") == null
                            ? Map.of()
                            : optionalObject(object, "metadata").values());
        } catch (RuntimeException exception) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Message is semantically invalid.", exception);
        }
    }

    private static Content decodeContent(StateValue.ObjectValue object) {
        String kind = requireString(object, "kind");
        HostingValidation.rejectUnknown(object.values().keySet(), contentFields(kind), "Content");
        try {
            return CONTENT_CODEC.decode(object, ContentStateCodec.VERSION);
        } catch (RuntimeException exception) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Content is semantically invalid.", exception);
        }
    }

    private static Set<String> contentFields(String kind) {
        Set<String> common = Set.of("kind", "metadata");
        Set<String> specific =
                switch (kind) {
                    case "text" -> Set.of("text");
                    case "reasoning" -> Set.of("id", "text", "protectedData");
                    case "data" -> Set.of("mediaType", "uri");
                    case "uri" -> Set.of("uri", "mediaType");
                    case "error" -> Set.of("message", "errorCode", "details");
                    case "functionCall" -> Set.of("callId", "name", "arguments", "informationalOnly");
                    case "functionResult" -> Set.of("callId", "result", "items", "error");
                    case "usage" -> Set.of("usage");
                    case "metadata" -> Set.of("values");
                    default ->
                        throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Unknown content discriminator.");
                };
        java.util.HashSet<String> allowed = new java.util.HashSet<>(common);
        allowed.addAll(specific);
        return Set.copyOf(allowed);
    }

    private static RunOptions decodeOptions(StateValue.ObjectValue object) {
        if (object == null) {
            return RunOptions.empty();
        }
        HostingValidation.rejectUnknown(object.values().keySet(), OPTIONS_FIELDS, "Run options");
        Integer maxIterations = optionalPositiveInt(object, "maxIterations");
        Integer maxFunctionCalls = optionalPositiveInt(object, "maxFunctionCalls");
        Map<String, StateValue> metadata = optionalObject(object, "metadata") == null
                ? Map.of()
                : optionalObject(object, "metadata").values();
        try {
            return new RunOptions(maxIterations, maxFunctionCalls, metadata);
        } catch (RuntimeException exception) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Run options are semantically invalid.", exception);
        }
    }

    private static Integer optionalPositiveInt(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof StateValue.NumberValue number
                && number.value().scale() <= 0
                && number.value().signum() > 0) {
            try {
                return number.value().intValueExact();
            } catch (ArithmeticException ignored) {
                // Fall through to the stable error.
            }
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be a positive integer when present.");
    }

    private static StateValue.ObjectValue messageValue(Message message) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("role", StateValue.string(message.role().value()));
        value.put(
                "contents",
                StateValue.array(message.contents().stream()
                        .map(HostingWireValues::contentValue)
                        .toList()));
        putString(value, "authorName", message.authorName());
        putString(value, "messageId", message.messageId());
        value.put("metadata", StateValue.object(message.metadata()));
        return requireObject(HostingRedactor.redact(StateValue.object(value)), "redacted message");
    }

    private static StateValue contentValue(Content content) {
        return HostingRedactor.redact(CONTENT_CODEC.encode(content));
    }

    private static StateValue.ObjectValue continuationValue(HostingContinuationDescriptor continuation) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("token", StateValue.string(continuation.token()));
        value.put("type", StateValue.string(continuation.type().value()));
        value.put("expiresAt", StateValue.string(continuation.expiresAt().toString()));
        value.put(
                "approvalRequests",
                StateValue.array(continuation.approvalRequests().stream()
                        .map(HostingWireValues::approvalRequestValue)
                        .toList()));
        value.put("processLocal", StateValue.bool(true));
        value.put("oneTime", StateValue.bool(true));
        return StateValue.object(value);
    }

    private static StateValue approvalRequestValue(HostingApprovalRequest request) {
        return StateValue.object(Map.of(
                "approvalId",
                StateValue.string(request.approvalId()),
                "callId",
                StateValue.string(request.callId()),
                "toolName",
                StateValue.string(request.toolName()),
                "arguments",
                HostingRedactor.redact(request.arguments())));
    }

    private static StateValue.ObjectValue errorBody(HostingError error) {
        return StateValue.object(Map.of(
                "code",
                StateValue.string(error.code().value()),
                "message",
                StateValue.string(error.message()),
                "retryable",
                StateValue.bool(error.retryable()),
                "details",
                HostingRedactor.redact(StateValue.object(error.details()))));
    }

    private static LinkedHashMap<String, StateValue> base(String wireVersion, String type) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("version", StateValue.string(wireVersion));
        value.put("type", StateValue.string(type));
        return value;
    }

    private static StateValue simpleValue(Object value) {
        return switch (value) {
            case StateValue state -> HostingRedactor.redact(state);
            case String string -> StateValue.string(string);
            case Integer integer -> StateValue.integer(integer);
            case Long longValue -> StateValue.integer(longValue);
            case Short shortValue -> StateValue.integer(shortValue);
            case Byte byteValue -> StateValue.integer(byteValue);
            case BigDecimal decimal -> StateValue.number(decimal);
            case Boolean bool -> StateValue.bool(bool);
            default -> StateValue.string("[UNSERIALIZED]");
        };
    }

    private static void requireVersion(StateValue.ObjectValue object, String wireVersion) {
        if (!wireVersion.equals(requireString(object, "version"))) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Unsupported Java hosting wire version.");
        }
    }

    private static StateValue optionalValue(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == StateValue.NullValue.INSTANCE ? null : value;
    }

    private static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        return requireObject(value, name);
    }

    private static List<StateValue> optionalArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return List.of();
        }
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be an array when present.");
    }

    private static List<StateValue> requireArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Member '" + name + "' must be an array.");
    }

    private static void putString(Map<String, StateValue> values, String name, String value) {
        if (value != null) {
            values.put(name, StateValue.string(value));
        }
    }
}
