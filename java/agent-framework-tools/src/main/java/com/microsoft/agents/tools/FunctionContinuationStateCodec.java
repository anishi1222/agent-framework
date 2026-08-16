// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes safe approval-continuation state using stable explicit discriminators.
 *
 * <p>The codec never serializes tools, handlers, clients, executors, middleware, or Java class
 * names. Unknown additive properties are ignored within version 1.
 */
public final class FunctionContinuationStateCodec implements StateCodec<FunctionContinuation> {
    /** Stable registered type identifier. */
    public static final String TYPE_ID = "com.microsoft.agents.tools.function-continuation";

    /** Initial codec version. */
    public static final int VERSION = 1;

    private final ContentStateCodec contentCodec = new ContentStateCodec();

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public int currentVersion() {
        return VERSION;
    }

    @Override
    public StateValue encode(FunctionContinuation value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("logicalRunId", StateValue.string(value.logicalRunId()));
        fields.put("history", encodeMessages(value.history()));
        fields.put(
                "approvalRequests",
                StateValue.array(value.approvalRequests().stream()
                        .map(this::encodeApprovalRequest)
                        .toList()));
        fields.put(
                "pendingCalls",
                StateValue.array(value.pendingCalls().stream()
                        .map(this::encodePendingCall)
                        .toList()));
        fields.put("options", encodeOptions(value.options()));
        fields.put("metadata", StateValue.object(value.metadata()));
        fields.put("toolMode", StateValue.string(value.toolMode().name()));
        fields.put("suspensionVersion", StateValue.integer(value.suspensionVersion()));
        fields.put("modelTurns", StateValue.integer(value.modelTurns()));
        fields.put("toolInvocations", StateValue.integer(value.toolInvocations()));
        fields.put("latestResponse", encodeResponse(value.latestResponse()));
        if (value.usage() != null) {
            fields.put("usage", StateValue.object(value.usage().values()));
        }
        return StateValue.object(fields);
    }

    @Override
    public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
        throw new SerializationException(
                SerializationError.CODEC_MIGRATION,
                "Function continuation migration from " + fromVersion + " to " + toVersion + " is unavailable.");
    }

    @Override
    public FunctionContinuation decode(StateValue value, int version) {
        if (version != VERSION) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    "Unsupported function continuation codec version " + version + ".");
        }
        StateValue.ObjectValue object = requireObject(value, "function continuation");
        try {
            return new FunctionContinuation(
                    requireString(object, "logicalRunId"),
                    decodeMessages(object.require("history")),
                    decodeArray(object, "approvalRequests").stream()
                            .map(this::decodeApprovalRequest)
                            .toList(),
                    decodeArray(object, "pendingCalls").stream()
                            .map(this::decodePendingCall)
                            .toList(),
                    decodeOptions(object.require("options")),
                    requireObject(object.require("metadata"), "metadata").values(),
                    ToolMode.valueOf(requireString(object, "toolMode")),
                    requireLong(object, "suspensionVersion"),
                    requireInt(object, "modelTurns"),
                    requireInt(object, "toolInvocations"),
                    decodeResponse(object.require("latestResponse")),
                    optionalUsage(object, "usage"));
        } catch (SerializationException exception) {
            throw exception;
        } catch (ValidationException | IllegalArgumentException exception) {
            throw malformed("Invalid function continuation.", exception);
        }
    }

    private StateValue encodePendingCall(FunctionContinuationCall pending) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("call", contentCodec.encode(pending.call()));
        fields.put("invocationId", StateValue.string(pending.invocationId().value()));
        fields.put("requestDigest", StateValue.string(pending.requestDigest()));
        if (pending.arguments() != null) {
            fields.put("arguments", pending.arguments());
        }
        putString(fields, "preparationError", pending.preparationError());
        fields.put("duplicate", StateValue.bool(pending.duplicate()));
        if (pending.approvalRequest() != null) {
            fields.put("approvalRequest", encodeApprovalRequest(pending.approvalRequest()));
        }
        if (pending.approvalDecision() != null) {
            fields.put("approvalDecision", encodeApprovalDecision(pending.approvalDecision()));
        }
        return StateValue.object(fields);
    }

    private FunctionContinuationCall decodePendingCall(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "pending call");
        Content content = contentCodec.decode(object.require("call"), ContentStateCodec.VERSION);
        if (!(content instanceof FunctionCallContent call)) {
            throw malformed("pending call content must use the functionCall discriminator.");
        }
        return new FunctionContinuationCall(
                call,
                new InvocationId(requireString(object, "invocationId")),
                requireString(object, "requestDigest"),
                optionalObject(object, "arguments"),
                optionalString(object, "preparationError"),
                optionalBoolean(object, "duplicate", false),
                optionalApprovalRequest(object, "approvalRequest"),
                optionalApprovalDecision(object, "approvalDecision"));
    }

    private StateValue encodeApprovalRequest(ToolApprovalRequest request) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("approvalId", StateValue.string(request.approvalId().value()));
        fields.put("logicalRunId", StateValue.string(request.logicalRunId()));
        fields.put("invocationId", StateValue.string(request.invocationId().value()));
        fields.put("callId", StateValue.string(request.callId()));
        fields.put("toolName", StateValue.string(request.toolName()));
        fields.put("schemaDigest", StateValue.string(request.schemaDigest()));
        fields.put("argumentsDigest", StateValue.string(request.argumentsDigest()));
        fields.put("requestDigest", StateValue.string(request.requestDigest()));
        fields.put("arguments", request.arguments());
        fields.put("state", StateValue.string(request.state().name()));
        return StateValue.object(fields);
    }

    private ToolApprovalRequest decodeApprovalRequest(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "approval request");
        return new ToolApprovalRequest(
                new ToolApprovalId(requireString(object, "approvalId")),
                requireString(object, "logicalRunId"),
                new InvocationId(requireString(object, "invocationId")),
                requireString(object, "callId"),
                requireString(object, "toolName"),
                requireString(object, "schemaDigest"),
                requireString(object, "argumentsDigest"),
                requireString(object, "requestDigest"),
                requireObject(object.require("arguments"), "arguments"),
                ToolApprovalState.valueOf(requireString(object, "state")));
    }

    private StateValue encodeApprovalDecision(ToolApprovalDecision decision) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("approvalId", StateValue.string(decision.approvalId().value()));
        fields.put("invocationId", StateValue.string(decision.invocationId().value()));
        fields.put("requestDigest", StateValue.string(decision.requestDigest()));
        fields.put("state", StateValue.string(decision.state().name()));
        putString(fields, "reason", decision.reason());
        return StateValue.object(fields);
    }

    private ToolApprovalDecision decodeApprovalDecision(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "approval decision");
        return new ToolApprovalDecision(
                new ToolApprovalId(requireString(object, "approvalId")),
                new InvocationId(requireString(object, "invocationId")),
                requireString(object, "requestDigest"),
                ToolApprovalState.valueOf(requireString(object, "state")),
                optionalString(object, "reason"));
    }

    private StateValue encodeOptions(FunctionInvocationOptions options) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("maxIterations", StateValue.integer(options.maxIterations()));
        if (options.maxFunctionCalls() != null) {
            fields.put("maxFunctionCalls", StateValue.integer(options.maxFunctionCalls()));
        }
        fields.put("toolMode", StateValue.string(options.toolMode().name()));
        fields.put("includeDetailedErrors", StateValue.bool(options.includeDetailedErrors()));
        fields.put("maxBufferedUpdates", StateValue.integer(options.maxBufferedUpdates()));
        return StateValue.object(fields);
    }

    private FunctionInvocationOptions decodeOptions(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "options");
        return new FunctionInvocationOptions(
                requireInt(object, "maxIterations"),
                optionalInt(object, "maxFunctionCalls"),
                ToolMode.valueOf(requireString(object, "toolMode")),
                optionalBoolean(object, "includeDetailedErrors", false),
                requireInt(object, "maxBufferedUpdates"));
    }

    private StateValue encodeResponse(ChatResponse response) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("messages", encodeMessages(response.messages()));
        putString(fields, "responseId", response.responseId());
        putString(fields, "conversationId", response.conversationId());
        putString(fields, "model", response.model());
        if (response.createdAt() != null) {
            fields.put("createdAt", StateValue.string(response.createdAt().toString()));
        }
        if (response.finishReason() != null) {
            fields.put("finishReason", StateValue.string(response.finishReason().value()));
        }
        if (response.usage() != null) {
            fields.put("usage", StateValue.object(response.usage().values()));
        }
        if (response.continuationToken() != null) {
            fields.put("continuationToken", response.continuationToken());
        }
        fields.put("metadata", StateValue.object(response.metadata()));
        fields.put(
                "updateSequences",
                StateValue.array(response.updateSequences().stream()
                        .map(StateValue::integer)
                        .toList()));
        return StateValue.object(fields);
    }

    private ChatResponse decodeResponse(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "latestResponse");
        return new ChatResponse(
                decodeMessages(object.require("messages")),
                optionalString(object, "responseId"),
                optionalString(object, "conversationId"),
                optionalString(object, "model"),
                optionalInstant(object, "createdAt"),
                optionalFinishReason(object, "finishReason"),
                optionalUsage(object, "usage"),
                object.values().get("continuationToken"),
                optionalObjectMap(object, "metadata"),
                decodeLongs(object, "updateSequences"));
    }

    private StateValue encodeMessages(List<Message> messages) {
        return StateValue.array(messages.stream().map(this::encodeMessage).toList());
    }

    private StateValue encodeMessage(Message message) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("role", StateValue.string(message.role().value()));
        fields.put(
                "contents",
                StateValue.array(
                        message.contents().stream().map(contentCodec::encode).toList()));
        putString(fields, "authorName", message.authorName());
        putString(fields, "messageId", message.messageId());
        fields.put("metadata", StateValue.object(message.metadata()));
        return StateValue.object(fields);
    }

    private List<Message> decodeMessages(StateValue value) {
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed("messages must be an array.");
        }
        ArrayList<Message> result = new ArrayList<>(array.values().size());
        for (StateValue item : array.values()) {
            StateValue.ObjectValue object = requireObject(item, "message");
            List<Content> contents = decodeArray(object, "contents").stream()
                    .map(content -> contentCodec.decode(content, ContentStateCodec.VERSION))
                    .toList();
            result.add(new Message(
                    Role.of(requireString(object, "role")),
                    contents,
                    optionalString(object, "authorName"),
                    optionalString(object, "messageId"),
                    optionalObjectMap(object, "metadata")));
        }
        return List.copyOf(result);
    }

    private ToolApprovalRequest optionalApprovalRequest(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? null : decodeApprovalRequest(value);
    }

    private ToolApprovalDecision optionalApprovalDecision(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? null : decodeApprovalDecision(value);
    }

    private static StateValue.ObjectValue optionalObject(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? null : requireObject(value, name);
    }

    private static Map<String, StateValue> optionalObjectMap(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? Map.of() : requireObject(value, name).values();
    }

    private static UsageDetails optionalUsage(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null
                ? null
                : new UsageDetails(requireObject(value, name).values());
    }

    private static FinishReason optionalFinishReason(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        return value == null ? null : FinishReason.of(value);
    }

    private static Instant optionalInstant(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw malformed(name + " must be an ISO-8601 instant.", exception);
        }
    }

    private static List<Long> decodeLongs(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed(name + " must be an array.");
        }
        return array.values().stream()
                .map(FunctionContinuationStateCodec::requireLong)
                .toList();
    }

    private static List<StateValue> decodeArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw malformed(name + " must be an array.");
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw malformed(name + " must be an object.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed(name + " must be a string.");
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed(name + " must be a string when present.");
    }

    private static boolean optionalBoolean(StateValue.ObjectValue object, String name, boolean defaultValue) {
        StateValue value = object.values().get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw malformed(name + " must be a Boolean.");
    }

    private static int requireInt(StateValue.ObjectValue object, String name) {
        return Math.toIntExact(requireLong(object.require(name)));
    }

    private static Integer optionalInt(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? null : Math.toIntExact(requireLong(value));
    }

    private static long requireLong(StateValue.ObjectValue object, String name) {
        return requireLong(object.require(name));
    }

    private static long requireLong(StateValue value) {
        if (!(value instanceof StateValue.NumberValue number) || number.value().scale() > 0) {
            throw malformed("Expected an integer number.");
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw malformed("Integer is outside the supported long range.", exception);
        }
    }

    private static void putString(Map<String, StateValue> fields, String name, String value) {
        if (value != null) {
            fields.put(name, StateValue.string(value));
        }
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }

    private static SerializationException malformed(String message, Throwable cause) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message, cause);
    }
}
