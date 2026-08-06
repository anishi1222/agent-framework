// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionContinuation;
import com.microsoft.agents.tools.FunctionContinuationStateCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PendingAgentRunStateCodec {
    private static final String KIND = "chat-agent-continuation";

    private static final int VERSION = 1;

    private final FunctionContinuationStateCodec functionCodec = new FunctionContinuationStateCodec();

    private final ContentStateCodec contentCodec = new ContentStateCodec();

    StateValue.ObjectValue encode(PendingAgentRunState pending) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("kind", StateValue.string(KIND));
        fields.put("version", StateValue.integer(VERSION));
        fields.put("continuationId", StateValue.string(pending.continuation().continuationId()));
        if (pending.continuation().sessionId() != null) {
            fields.put("sessionId", StateValue.string(pending.continuation().sessionId()));
        }
        fields.put("logicalRunId", StateValue.string(pending.continuation().logicalRunId()));
        fields.put("restartCapable", StateValue.bool(pending.continuation().restartCapable()));
        fields.put(
                "exactlyOnceAfterRestart",
                StateValue.bool(pending.continuation().exactlyOnceAfterRestart()));
        fields.put("functionContinuation", functionCodec.encode(pending.functionContinuation()));
        fields.put(
                "inputMessages",
                StateValue.array(pending.inputMessages().stream()
                        .map(this::encodeMessage)
                        .toList()));
        fields.put("options", encodeRunOptions(pending.options()));
        fields.put("initialMessageCount", StateValue.integer(pending.initialMessageCount()));
        return StateValue.object(fields);
    }

    PendingAgentRunState decode(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "pendingRun");
        if (!KIND.equals(requireString(object, "kind"))) {
            throw malformed("Unknown pending-run kind.");
        }
        if (requireInt(object, "version") != VERSION) {
            throw malformed("Unsupported pending-run version.");
        }
        FunctionContinuation functionContinuation =
                functionCodec.decode(object.require("functionContinuation"), FunctionContinuationStateCodec.VERSION);
        AgentContinuation continuation = new AgentContinuation(
                requireString(object, "continuationId"),
                optionalString(object, "sessionId"),
                requireString(object, "logicalRunId"),
                functionContinuation.approvalRequests(),
                optionalBoolean(object, "restartCapable", false),
                optionalBoolean(object, "exactlyOnceAfterRestart", false));
        return new PendingAgentRunState(
                continuation,
                functionContinuation,
                decodeMessages(object.require("inputMessages")),
                decodeRunOptions(object.require("options")),
                requireInt(object, "initialMessageCount"));
    }

    private StateValue encodeRunOptions(RunOptions options) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        if (options.maxIterations() != null) {
            fields.put("maxIterations", StateValue.integer(options.maxIterations()));
        }
        if (options.maxFunctionCalls() != null) {
            fields.put("maxFunctionCalls", StateValue.integer(options.maxFunctionCalls()));
        }
        fields.put("metadata", StateValue.object(options.metadata()));
        return StateValue.object(fields);
    }

    private RunOptions decodeRunOptions(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "options");
        return new RunOptions(
                optionalInt(object, "maxIterations"),
                optionalInt(object, "maxFunctionCalls"),
                optionalObjectMap(object, "metadata"));
    }

    private StateValue encodeMessage(Message message) {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("role", StateValue.string(message.role().value()));
        fields.put(
                "contents",
                StateValue.array(
                        message.contents().stream().map(contentCodec::encode).toList()));
        if (message.authorName() != null) {
            fields.put("authorName", StateValue.string(message.authorName()));
        }
        if (message.messageId() != null) {
            fields.put("messageId", StateValue.string(message.messageId()));
        }
        fields.put("metadata", StateValue.object(message.metadata()));
        return StateValue.object(fields);
    }

    private List<Message> decodeMessages(StateValue value) {
        if (!(value instanceof StateValue.ArrayValue array)) {
            throw malformed("inputMessages must be an array.");
        }
        ArrayList<Message> messages = new ArrayList<>(array.values().size());
        for (StateValue item : array.values()) {
            StateValue.ObjectValue object = requireObject(item, "message");
            StateValue contentsValue = object.require("contents");
            if (!(contentsValue instanceof StateValue.ArrayValue contentsArray)) {
                throw malformed("message contents must be an array.");
            }
            List<Content> contents = contentsArray.values().stream()
                    .map(content -> contentCodec.decode(content, ContentStateCodec.VERSION))
                    .toList();
            messages.add(new Message(
                    Role.of(requireString(object, "role")),
                    contents,
                    optionalString(object, "authorName"),
                    optionalString(object, "messageId"),
                    optionalObjectMap(object, "metadata")));
        }
        return List.copyOf(messages);
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

    private static long requireLong(StateValue value) {
        if (!(value instanceof StateValue.NumberValue number) || number.value().scale() > 0) {
            throw malformed("Expected an integer number.");
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw malformed("Integer is outside the supported range.", exception);
        }
    }

    private static Map<String, StateValue> optionalObjectMap(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        return value == null ? Map.of() : requireObject(value, name).values();
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }

    private static SerializationException malformed(String message, Throwable cause) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message, cause);
    }
}
