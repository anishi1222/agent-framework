// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.ContentStateCodec;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class OpenAIResponsesResponseMapper {
    private static final ContentStateCodec CONTENT_CODEC = new ContentStateCodec();

    private final OpenAIResponsesJsonCodec codec;

    private final OpenAIResponsesHostingRoute route;

    private final OpenAIResponsesRunRequest request;

    private final String responseId;

    private final Instant createdAt;

    OpenAIResponsesResponseMapper(
            OpenAIResponsesJsonCodec codec,
            OpenAIResponsesHostingRoute route,
            OpenAIResponsesRunRequest request,
            String responseId,
            Clock clock) {
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        this.route = java.util.Objects.requireNonNull(route, "route");
        this.request = java.util.Objects.requireNonNull(request, "request");
        this.responseId = requireNonBlank(responseId, "responseId");
        createdAt = java.util.Objects.requireNonNull(clock, "clock").instant();
    }

    MappedResponse mapFinite(HostingOutcome outcome) {
        java.util.Objects.requireNonNull(outcome, "outcome");
        if (outcome.status() != HostingOutcomeStatus.COMPLETED) {
            throw outcomeFailure(outcome);
        }
        StateValue.ObjectValue result = requireObject(outcome.result(), "hosted agent result");
        List<Message> messages = decodeMessages(optionalArray(result, "messages"));
        List<StateValue> output = outputItems(messages);
        StateValue usage = usageValue(optionalObject(result, "usage"));
        Instant responseTime = optionalInstant(result, "createdAt");
        StateValue.ObjectValue response =
                responseValue("completed", output, usage, null, responseTime == null ? createdAt : responseTime);
        return new MappedResponse(response, messages);
    }

    StreamingAccumulator newStreamingAccumulator() {
        return new StreamingAccumulator();
    }

    StateValue.ObjectValue failureResponse(
            HostingError error, String status, List<StateValue> output, StateValue usage) {
        return responseValue(status, output, usage, java.util.Objects.requireNonNull(error, "error"), createdAt);
    }

    private StateValue.ObjectValue responseValue(
            String status, List<StateValue> output, StateValue usage, HostingError error, Instant responseTime) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("id", StateValue.string(responseId));
        value.put("object", StateValue.string("response"));
        value.put("created_at", StateValue.integer(responseTime.getEpochSecond()));
        value.put("status", StateValue.string(status));
        value.put(
                "error",
                error == null
                        ? StateValue.nullValue()
                        : StateValue.object(Map.of(
                                "code", StateValue.string(error.code().value()),
                                "message", StateValue.string(error.message()))));
        value.put("incomplete_details", StateValue.nullValue());
        value.put("model", StateValue.string(model()));
        value.put("output", StateValue.array(output));
        putNullableString(value, "instructions", request.requestInfo().instructions());
        value.put("usage", usage);
        value.put(
                "parallel_tool_calls",
                StateValue.bool(request.requestInfo().parallelToolCalls() == null
                        || request.requestInfo().parallelToolCalls()));
        value.put("tools", StateValue.array(request.requestInfo().tools()));
        value.put(
                "tool_choice",
                request.requestInfo().toolChoice() == null
                        ? StateValue.string(request.requestInfo().tools().isEmpty() ? "none" : "auto")
                        : request.requestInfo().toolChoice());
        putNullableNumber(value, "temperature", request.requestInfo().temperature());
        putNullableNumber(value, "top_p", request.requestInfo().topP());
        value.put("metadata", metadataValue());
        value.put(
                "conversation",
                request.conversationId() == null
                        ? StateValue.nullValue()
                        : StateValue.object(Map.of("id", StateValue.string(request.conversationId()))));
        putNullableInteger(value, "max_output_tokens", request.requestInfo().maxOutputTokens());
        putNullableString(value, "previous_response_id", request.previousResponseId());
        value.put("store", StateValue.bool(request.store()));
        putNullableInteger(value, "max_tool_calls", request.requestInfo().maxToolCalls());
        putNullableString(value, "user", request.requestInfo().user());
        value.put("agent", StateValue.object(Map.of("name", StateValue.string(route.routeId()))));
        return StateValue.object(value);
    }

    private List<StateValue> outputItems(List<Message> messages) {
        ArrayList<StateValue> output = new ArrayList<>();
        for (Message message : messages) {
            ArrayList<StateValue> messageContent = new ArrayList<>();
            String messageId = message.messageId() == null ? newId("msg") : message.messageId();
            for (Content content : message.contents()) {
                if (content instanceof TextContent text) {
                    requireAssistantRole(message, content);
                    messageContent.add(outputText(text.text(), text.metadata()));
                } else if (content instanceof FunctionCallContent call) {
                    requireAssistantRole(message, content);
                    output.add(functionCallItem(call));
                } else if (content instanceof FunctionResultContent result) {
                    output.add(functionResultItem(result));
                } else if (content instanceof ReasoningContent reasoning) {
                    requireAssistantRole(message, content);
                    output.add(reasoningItem(reasoning));
                } else if (content instanceof DataContent data) {
                    requireAssistantRole(message, content);
                    output.add(imageItem(data));
                } else if (content instanceof UriContent uri) {
                    requireAssistantRole(message, content);
                    messageContent.add(outputText(uri.uri().toString(), uri.metadata()));
                } else {
                    throw new HostingException(
                            HostingErrorCode.INTERNAL_ERROR,
                            "Hosted agent returned content unsupported by OpenAI Responses hosting.");
                }
            }
            if (!messageContent.isEmpty()) {
                output.add(messageItem(messageId, "assistant", "completed", messageContent));
            }
        }
        return List.copyOf(output);
    }

    private static void requireAssistantRole(Message message, Content content) {
        if (!Role.ASSISTANT.equals(message.role())) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR,
                    "Hosted " + content.kind() + " output must use the assistant role.");
        }
    }

    private static List<Message> decodeMessages(List<StateValue> values) {
        ArrayList<Message> messages = new ArrayList<>(values.size());
        for (StateValue value : values) {
            StateValue.ObjectValue object = requireObject(value, "agent response message");
            String roleValue = requireString(object, "role");
            Role role = Role.of(roleValue);
            ArrayList<Content> contents = new ArrayList<>();
            for (StateValue content : optionalArray(object, "contents")) {
                contents.add(CONTENT_CODEC.decode(content, ContentStateCodec.VERSION));
            }
            messages.add(new Message(
                    role,
                    contents,
                    optionalString(object, "authorName"),
                    optionalString(object, "messageId"),
                    optionalStateMap(object, "metadata")));
        }
        return List.copyOf(messages);
    }

    private StateValue.ObjectValue functionCallItem(FunctionCallContent call) {
        if (!(call.arguments() instanceof StateValue.ObjectValue)) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Hosted function-call arguments must contain a JSON object.");
        }
        String itemId = metadataString(call.metadata(), "openai.itemId");
        if (itemId == null) {
            itemId = newId("fc");
        }
        return StateValue.object(Map.of(
                "id", StateValue.string(itemId),
                "type", StateValue.string("function_call"),
                "call_id", StateValue.string(call.callId()),
                "name", StateValue.string(call.name()),
                "arguments", StateValue.string(jsonText(call.arguments())),
                "status", StateValue.string("completed")));
    }

    private StateValue.ObjectValue functionResultItem(FunctionResultContent result) {
        return StateValue.object(Map.of(
                "id", StateValue.string(newId("fco")),
                "type", StateValue.string("function_call_output"),
                "call_id", StateValue.string(result.callId()),
                "output", StateValue.string(jsonText(result.result())),
                "status", StateValue.string("completed")));
    }

    private static StateValue.ObjectValue reasoningItem(ReasoningContent reasoning) {
        LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
        value.put("id", StateValue.string(reasoning.id() == null ? newId("rs") : reasoning.id()));
        value.put("type", StateValue.string("reasoning"));
        value.put("status", StateValue.string("completed"));
        value.put(
                "summary",
                reasoning.text() == null
                        ? StateValue.array(List.of())
                        : StateValue.array(List.of(StateValue.object(Map.of(
                                "type", StateValue.string("summary_text"),
                                "text", StateValue.string(reasoning.text()))))));
        value.put(
                "encrypted_content",
                reasoning.protectedData() == null
                        ? StateValue.nullValue()
                        : StateValue.string(reasoning.protectedData()));
        return StateValue.object(value);
    }

    private static StateValue.ObjectValue imageItem(DataContent data) {
        if (!data.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Hosted binary output must use an image media type.");
        }
        return StateValue.object(Map.of(
                "id", StateValue.string(newId("ig")),
                "type", StateValue.string("image_generation_call"),
                "status", StateValue.string("completed"),
                "result", StateValue.string(Base64.getEncoder().encodeToString(data.data()))));
    }

    private static StateValue.ObjectValue messageItem(String id, String role, String status, List<StateValue> content) {
        return StateValue.object(Map.of(
                "id", StateValue.string(id),
                "type", StateValue.string("message"),
                "status", StateValue.string(status),
                "role", StateValue.string(role),
                "content", StateValue.array(content)));
    }

    private static StateValue.ObjectValue outputText(String text, Map<String, StateValue> metadata) {
        boolean refusal = StateValue.bool(true).equals(metadata.get("openai.refusal"));
        if (refusal) {
            return StateValue.object(Map.of(
                    "type", StateValue.string("refusal"),
                    "refusal", StateValue.string(text)));
        }
        return StateValue.object(Map.of(
                "type", StateValue.string("output_text"),
                "text", StateValue.string(text),
                "annotations", StateValue.array(List.of()),
                "logprobs", StateValue.array(List.of())));
    }

    private StateValue usageValue(StateValue.ObjectValue usage) {
        StateValue input = usageNumber(usage, "inputTokens");
        StateValue output = usageNumber(usage, "outputTokens");
        StateValue total = usageNumber(usage, "totalTokens");
        if (total instanceof StateValue.NumberValue number
                && number.value().compareTo(BigDecimal.ZERO) == 0
                && input instanceof StateValue.NumberValue inputNumber
                && output instanceof StateValue.NumberValue outputNumber) {
            total = StateValue.number(inputNumber.value().add(outputNumber.value()));
        }
        return StateValue.object(Map.of(
                "input_tokens",
                input,
                "input_tokens_details",
                StateValue.object(Map.of("cached_tokens", usageNumber(usage, "cacheReadInputTokens"))),
                "output_tokens",
                output,
                "output_tokens_details",
                StateValue.object(Map.of("reasoning_tokens", usageNumber(usage, "reasoningOutputTokens"))),
                "total_tokens",
                total));
    }

    private static StateValue usageNumber(StateValue.ObjectValue usage, String name) {
        if (usage == null) {
            return StateValue.integer(0);
        }
        StateValue value = usage.values().get(name);
        return value instanceof StateValue.NumberValue ? value : StateValue.integer(0);
    }

    private StateValue.ObjectValue metadataValue() {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        request.requestInfo().metadata().forEach((key, value) -> values.put(key, StateValue.string(value)));
        return StateValue.object(values);
    }

    private String model() {
        return request.requestInfo().model() == null
                ? route.model()
                : request.requestInfo().model();
    }

    private String jsonText(StateValue value) {
        return new String(codec.encodeValue(value), StandardCharsets.UTF_8);
    }

    private static HostingException outcomeFailure(HostingOutcome outcome) {
        if (outcome.error() != null) {
            return new HostingException(outcome.error());
        }
        return new HostingException(
                HostingErrorCode.INTERNAL_ERROR, "Hosted execution did not produce a completed OpenAI response.");
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new HostingException(HostingErrorCode.INTERNAL_ERROR, name + " has an invalid framework shape.");
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
        throw new HostingException(HostingErrorCode.INTERNAL_ERROR, name + " has an invalid framework shape.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            throw new HostingException(HostingErrorCode.INTERNAL_ERROR, name + " is absent from a framework value.");
        }
        return value;
    }

    private static String optionalString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return null;
        }
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw new HostingException(HostingErrorCode.INTERNAL_ERROR, name + " has an invalid framework shape.");
    }

    private static Instant optionalInstant(StateValue.ObjectValue object, String name) {
        String value = optionalString(object, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR, "Hosted response time has an invalid framework shape.", failure);
        }
    }

    private static Map<String, StateValue> optionalStateMap(StateValue.ObjectValue object, String name) {
        StateValue.ObjectValue value = optionalObject(object, name);
        return value == null ? Map.of() : value.values();
    }

    private static String metadataString(Map<String, StateValue> metadata, String name) {
        StateValue value = metadata.get(name);
        return value instanceof StateValue.StringValue string ? string.value() : null;
    }

    private static void putNullableString(Map<String, StateValue> target, String name, String value) {
        target.put(name, value == null ? StateValue.nullValue() : StateValue.string(value));
    }

    private static void putNullableInteger(Map<String, StateValue> target, String name, Integer value) {
        target.put(name, value == null ? StateValue.nullValue() : StateValue.integer(value));
    }

    private static void putNullableNumber(Map<String, StateValue> target, String name, Double value) {
        target.put(name, value == null ? StateValue.nullValue() : StateValue.number(BigDecimal.valueOf(value)));
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    record MappedResponse(StateValue.ObjectValue value, List<Message> messages) {
        MappedResponse {
            java.util.Objects.requireNonNull(value, "value");
            messages = List.copyOf(java.util.Objects.requireNonNull(messages, "messages"));
        }
    }

    record StreamEnvelope(String event, StateValue.ObjectValue data) {
        StreamEnvelope {
            event = requireNonBlank(event, "event");
            java.util.Objects.requireNonNull(data, "data");
        }
    }

    final class StreamingAccumulator {
        private final ArrayList<OutputEntry> entries = new ArrayList<>();

        private final LinkedHashMap<String, TextOutputEntry> textEntries = new LinkedHashMap<>();

        private StateValue usage = usageValue(null);

        private long sequence;

        private String fallbackMessageId;

        List<StreamEnvelope> start() {
            StateValue.ObjectValue response = responseValue("in_progress", List.of(), usage, null, createdAt);
            return List.of(
                    envelope("response.created", Map.of("response", response)),
                    envelope("response.in_progress", Map.of("response", response)));
        }

        List<StreamEnvelope> accept(HostingEvent event) {
            if (event.type() != HostingEventType.AGENT_UPDATE) {
                throw new HostingException(
                        HostingErrorCode.INTERNAL_ERROR,
                        "OpenAI Responses agent route emitted a non-agent hosting event.");
            }
            StateValue.ObjectValue update = requireObject(event.data(), "agent update");
            StateValue.ObjectValue updateUsage = optionalObject(update, "usage");
            if (updateUsage != null) {
                usage = usageValue(updateUsage);
            }
            String messageId = optionalString(update, "messageId");
            String role = optionalString(update, "role");
            if (messageId == null) {
                if (fallbackMessageId == null) {
                    fallbackMessageId = newId("msg");
                }
                messageId = fallbackMessageId;
            }
            ArrayList<StreamEnvelope> events = new ArrayList<>();
            for (StateValue value : optionalArray(update, "contents")) {
                Content content = CONTENT_CODEC.decode(value, ContentStateCodec.VERSION);
                requireStreamingRole(role, content);
                if (content instanceof TextContent text) {
                    appendText(messageId, text, events);
                } else if (content instanceof FunctionCallContent call) {
                    appendFunctionCall(call, events);
                } else if (content instanceof FunctionResultContent result) {
                    appendStatic(functionResultItem(result), result, events);
                } else if (content instanceof ReasoningContent reasoning) {
                    appendStatic(reasoningItem(reasoning), reasoning, events);
                } else if (content instanceof DataContent data) {
                    appendStatic(imageItem(data), data, events);
                } else if (content instanceof UriContent uri) {
                    appendText(messageId, new TextContent(uri.uri().toString(), uri.metadata()), events);
                } else {
                    throw new HostingException(
                            HostingErrorCode.INTERNAL_ERROR,
                            "Hosted stream returned content unsupported by OpenAI Responses hosting.");
                }
            }
            return List.copyOf(events);
        }

        private void requireStreamingRole(String role, Content content) {
            if (content instanceof FunctionResultContent
                    || role == null
                    || Role.ASSISTANT.value().equals(role)) {
                return;
            }
            throw new HostingException(
                    HostingErrorCode.INTERNAL_ERROR,
                    "Hosted " + content.kind() + " stream output must use the assistant role.");
        }

        List<StreamEnvelope> finishOutput() {
            ArrayList<StreamEnvelope> events = new ArrayList<>();
            for (TextOutputEntry entry : textEntries.values()) {
                if (entry.done) {
                    continue;
                }
                entry.done = true;
                StateValue.ObjectValue part = outputText(entry.text.toString(), entry.metadata);
                if (entry.refusal) {
                    events.add(envelope(
                            "response.refusal.done",
                            Map.of(
                                    "item_id", StateValue.string(entry.id),
                                    "output_index", StateValue.integer(entry.outputIndex),
                                    "content_index", StateValue.integer(0),
                                    "refusal", StateValue.string(entry.text.toString()))));
                } else {
                    events.add(envelope(
                            "response.output_text.done",
                            Map.of(
                                    "item_id", StateValue.string(entry.id),
                                    "output_index", StateValue.integer(entry.outputIndex),
                                    "content_index", StateValue.integer(0),
                                    "text", StateValue.string(entry.text.toString()),
                                    "logprobs", StateValue.array(List.of()))));
                }
                events.add(envelope(
                        "response.content_part.done",
                        Map.of(
                                "item_id", StateValue.string(entry.id),
                                "output_index", StateValue.integer(entry.outputIndex),
                                "content_index", StateValue.integer(0),
                                "part", part)));
                events.add(envelope(
                        "response.output_item.done",
                        Map.of(
                                "output_index", StateValue.integer(entry.outputIndex),
                                "item", entry.finalValue())));
            }
            return List.copyOf(events);
        }

        StateValue.ObjectValue completedResponse() {
            return responseValue("completed", outputValues(), usage, null, createdAt);
        }

        StateValue.ObjectValue terminalResponse(HostingError error, String status) {
            return failureResponse(error, status, outputValues(), usage);
        }

        List<Message> messages() {
            ArrayList<Content> contents = new ArrayList<>();
            for (OutputEntry entry : entries) {
                contents.add(entry.content());
            }
            return contents.isEmpty() ? List.of() : List.of(new Message(Role.ASSISTANT, contents));
        }

        StateValue usage() {
            return usage;
        }

        StreamEnvelope terminal(String event, StateValue.ObjectValue response) {
            return envelope(event, Map.of("response", response));
        }

        private void appendText(String messageId, TextContent content, List<StreamEnvelope> events) {
            boolean refusal = StateValue.bool(true).equals(content.metadata().get("openai.refusal"));
            TextOutputEntry entry = textEntries.get(messageId);
            if (entry == null) {
                entry = new TextOutputEntry(messageId, entries.size(), content.metadata(), refusal);
                textEntries.put(messageId, entry);
                entries.add(entry);
                events.add(envelope(
                        "response.output_item.added",
                        Map.of(
                                "output_index", StateValue.integer(entry.outputIndex),
                                "item", entry.inProgressValue())));
                events.add(envelope(
                        "response.content_part.added",
                        Map.of(
                                "item_id", StateValue.string(entry.id),
                                "output_index", StateValue.integer(entry.outputIndex),
                                "content_index", StateValue.integer(0),
                                "part", outputText("", content.metadata()))));
            } else if (entry.refusal != refusal) {
                throw new HostingException(
                        HostingErrorCode.INTERNAL_ERROR,
                        "Hosted stream changed one message between text and refusal content.");
            }
            entry.text.append(content.text());
            if (!content.text().isEmpty()) {
                LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
                fields.put("item_id", StateValue.string(entry.id));
                fields.put("output_index", StateValue.integer(entry.outputIndex));
                fields.put("content_index", StateValue.integer(0));
                fields.put("delta", StateValue.string(content.text()));
                if (!entry.refusal) {
                    fields.put("logprobs", StateValue.array(List.of()));
                }
                events.add(envelope(entry.refusal ? "response.refusal.delta" : "response.output_text.delta", fields));
            }
        }

        private void appendFunctionCall(FunctionCallContent call, List<StreamEnvelope> events) {
            StateValue.ObjectValue completed = functionCallItem(call);
            String itemId = requireString(completed, "id");
            int outputIndex = entries.size();
            StaticOutputEntry entry = new StaticOutputEntry(completed, call);
            entries.add(entry);
            LinkedHashMap<String, StateValue> pending = new LinkedHashMap<>(completed.values());
            pending.put("arguments", StateValue.string(""));
            pending.put("status", StateValue.string("in_progress"));
            String arguments = jsonText(call.arguments());
            events.add(envelope(
                    "response.output_item.added",
                    Map.of(
                            "output_index", StateValue.integer(outputIndex),
                            "item", StateValue.object(pending))));
            events.add(envelope(
                    "response.function_call_arguments.delta",
                    Map.of(
                            "item_id", StateValue.string(itemId),
                            "output_index", StateValue.integer(outputIndex),
                            "delta", StateValue.string(arguments))));
            events.add(envelope(
                    "response.function_call_arguments.done",
                    Map.of(
                            "item_id", StateValue.string(itemId),
                            "output_index", StateValue.integer(outputIndex),
                            "name", StateValue.string(call.name()),
                            "arguments", StateValue.string(arguments))));
            events.add(envelope(
                    "response.output_item.done",
                    Map.of("output_index", StateValue.integer(outputIndex), "item", completed)));
        }

        private void appendStatic(StateValue.ObjectValue value, Content content, List<StreamEnvelope> events) {
            int outputIndex = entries.size();
            entries.add(new StaticOutputEntry(value, content));
            events.add(envelope(
                    "response.output_item.added",
                    Map.of("output_index", StateValue.integer(outputIndex), "item", value)));
            events.add(envelope(
                    "response.output_item.done",
                    Map.of("output_index", StateValue.integer(outputIndex), "item", value)));
        }

        private List<StateValue> outputValues() {
            return entries.stream()
                    .map(OutputEntry::finalValue)
                    .map(StateValue.class::cast)
                    .toList();
        }

        private StreamEnvelope envelope(String event, Map<String, StateValue> fields) {
            LinkedHashMap<String, StateValue> value = new LinkedHashMap<>();
            value.put("type", StateValue.string(event));
            value.put("sequence_number", StateValue.integer(sequence++));
            value.putAll(fields);
            return new StreamEnvelope(event, StateValue.object(value));
        }
    }

    private sealed interface OutputEntry permits TextOutputEntry, StaticOutputEntry {
        StateValue.ObjectValue finalValue();

        Content content();
    }

    private static final class TextOutputEntry implements OutputEntry {
        private final String id;

        private final int outputIndex;

        private final Map<String, StateValue> metadata;

        private final boolean refusal;

        private final StringBuilder text = new StringBuilder();

        private boolean done;

        private TextOutputEntry(String id, int outputIndex, Map<String, StateValue> metadata, boolean refusal) {
            this.id = id;
            this.outputIndex = outputIndex;
            this.metadata = Map.copyOf(metadata);
            this.refusal = refusal;
        }

        private StateValue.ObjectValue inProgressValue() {
            return messageItem(id, "assistant", "in_progress", List.of());
        }

        @Override
        public StateValue.ObjectValue finalValue() {
            return messageItem(id, "assistant", "completed", List.of(outputText(text.toString(), metadata)));
        }

        @Override
        public Content content() {
            return new TextContent(text.toString(), metadata);
        }
    }

    private record StaticOutputEntry(StateValue.ObjectValue finalValue, Content content) implements OutputEntry {
        private StaticOutputEntry {
            java.util.Objects.requireNonNull(finalValue, "finalValue");
            java.util.Objects.requireNonNull(content, "content");
        }
    }
}
