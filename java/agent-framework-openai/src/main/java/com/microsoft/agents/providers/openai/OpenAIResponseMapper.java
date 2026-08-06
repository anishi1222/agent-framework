// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageDetails;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class OpenAIResponseMapper {
    private OpenAIResponseMapper() {}

    static ChatResponse map(OpenAITransport.Response response) {
        requireSuccessfulOrContinuable(response);
        ArrayList<Content> contents = new ArrayList<>();
        for (OpenAITransport.OutputItem output : response.outputs()) {
            contents.add(mapOutput(output));
        }
        List<Message> messages =
                contents.isEmpty() ? List.of() : List.of(new Message(Role.ASSISTANT, contents, null, null, Map.of()));
        return new ChatResponse(
                messages,
                response.responseId(),
                response.conversationId(),
                response.model(),
                response.createdAt(),
                finishReason(response),
                usage(response.usage()),
                continuation(response),
                metadata(response),
                List.of());
    }

    private static Content mapOutput(OpenAITransport.OutputItem output) {
        if (output instanceof OpenAITransport.TextOutput text) {
            return textContent(text.messageId(), text.text(), text.refusal(), text.metadata());
        }
        if (output instanceof OpenAITransport.ReasoningOutput reasoning) {
            return new ReasoningContent(
                    reasoning.id(),
                    reasoning.text(),
                    reasoning.protectedData(),
                    Map.of("openai.summary", StateValue.bool(reasoning.summary())));
        }
        if (output instanceof OpenAITransport.FunctionCallOutput call) {
            return new FunctionCallContent(
                    call.callId(),
                    call.name(),
                    call.arguments(),
                    false,
                    callMetadata(call.providerItemId(), call.status()));
        }
        if (output instanceof OpenAITransport.ImageOutput image) {
            if ("data".equalsIgnoreCase(image.uri().getScheme())) {
                DataContent data = DataContent.fromDataUri(image.uri().toString());
                if (!data.mediaType().equalsIgnoreCase(image.mediaType())) {
                    throw new OpenAIProtocolException(
                            "OpenAI generated-image media type conflicts with its data URI.",
                            null,
                            "conflicting_image_media_type");
                }
                return data;
            }
            return new UriContent(image.uri(), image.mediaType());
        }
        throw new OpenAIProtocolException("OpenAI returned an unsupported output item.", null, "unsupported_output");
    }

    private static void requireSuccessfulOrContinuable(OpenAITransport.Response response) {
        switch (response.status()) {
            case CANCELLED -> throw new RunCancelledException();
            case FAILED -> {
                String requestId = OpenAIProviderException.safeIdentifier(response.requestId());
                throw new OpenAIProviderException(
                        "OpenAI response failed" + (requestId == null ? "." : " (request " + requestId + ")."),
                        null,
                        response.requestId(),
                        response.errorCode());
            }
            case COMPLETED, INCOMPLETE, IN_PROGRESS, QUEUED -> {
                // Mapped below.
            }
        }
    }

    private static FinishReason finishReason(OpenAITransport.Response response) {
        if (response.status() == OpenAITransport.ResponseStatus.COMPLETED) {
            return response.outputs().stream().anyMatch(OpenAITransport.FunctionCallOutput.class::isInstance)
                    ? FinishReason.TOOL_CALLS
                    : FinishReason.STOP;
        }
        if (response.status() != OpenAITransport.ResponseStatus.INCOMPLETE) {
            return null;
        }
        return switch (response.incompleteReason() == null ? "" : response.incompleteReason()) {
            case "max_output_tokens" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "" -> FinishReason.of("incomplete");
            default -> FinishReason.of(response.incompleteReason());
        };
    }

    private static UsageDetails usage(OpenAITransport.Usage usage) {
        if (usage == null) {
            return null;
        }
        UsageDetails.Builder builder = UsageDetails.builder()
                .inputTokens(usage.inputTokens())
                .outputTokens(usage.outputTokens())
                .totalTokens(usage.totalTokens());
        if (usage.cachedInputTokens() != null) {
            builder.value(UsageDetails.CACHE_READ_INPUT_TOKENS, StateValue.integer(usage.cachedInputTokens()))
                    .value("openai.cachedInputTokens", StateValue.integer(usage.cachedInputTokens()));
        }
        if (usage.reasoningOutputTokens() != null) {
            builder.value(UsageDetails.REASONING_OUTPUT_TOKENS, StateValue.integer(usage.reasoningOutputTokens()))
                    .value("openai.reasoningOutputTokens", StateValue.integer(usage.reasoningOutputTokens()));
        }
        return builder.build();
    }

    private static StateValue continuation(OpenAITransport.Response response) {
        if (response.status() != OpenAITransport.ResponseStatus.IN_PROGRESS
                && response.status() != OpenAITransport.ResponseStatus.QUEUED) {
            return null;
        }
        return StateValue.object(Map.of(
                "provider", StateValue.string("openai"),
                "responseId", StateValue.string(response.responseId()),
                "status", StateValue.string(response.status().name().toLowerCase(java.util.Locale.ROOT))));
    }

    private static Map<String, StateValue> metadata(OpenAITransport.Response response) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(response.metadata());
        metadata.put("openai.status", StateValue.string(response.status().name().toLowerCase(java.util.Locale.ROOT)));
        if (response.requestId() != null) {
            metadata.put("openai.requestId", StateValue.string(response.requestId()));
        }
        if (response.errorCode() != null) {
            metadata.put("openai.errorCode", StateValue.string(response.errorCode()));
        }
        return Map.copyOf(metadata);
    }

    private static Map<String, StateValue> callMetadata(String itemId, String status) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        if (itemId != null) {
            metadata.put("openai.itemId", StateValue.string(itemId));
        }
        if (status != null) {
            metadata.put("openai.status", StateValue.string(status));
        }
        return Map.copyOf(metadata);
    }

    private static TextContent textContent(
            String messageId, String text, boolean refusal, Map<String, StateValue> sourceMetadata) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(sourceMetadata);
        StateValue expectedMessageId = StateValue.string(messageId);
        StateValue existingMessageId = metadata.putIfAbsent("openai.messageId", expectedMessageId);
        if (existingMessageId != null && !existingMessageId.equals(expectedMessageId)) {
            throw new OpenAIProtocolException(
                    "OpenAI text metadata contains a conflicting message identifier.", null, "conflicting_message_id");
        }
        if (refusal) {
            metadata.put("openai.refusal", StateValue.bool(true));
        }
        return new TextContent(text, metadata);
    }

    static final class StreamMapper {
        private final TreeMap<Long, CallState> calls = new TreeMap<>();

        private final Set<String> emittedTextIds = new HashSet<>();

        private final Set<String> emittedReasoningIds = new HashSet<>();

        private final Set<String> emittedCallIds = new HashSet<>();

        private boolean terminal;

        private String responseId;

        private String conversationId;

        private String model;

        private java.time.Instant createdAt;

        private String requestId;

        List<ChatResponseUpdate> map(OpenAITransport.StreamEvent event) {
            if (terminal) {
                throw new OpenAIProtocolException(
                        "OpenAI emitted an event after terminal completion.", requestId, "late_event");
            }
            if (event instanceof OpenAITransport.ResponseStarted started) {
                responseId = stable(responseId, started.responseId(), "responseId");
                conversationId = stable(conversationId, started.conversationId(), "conversationId");
                model = stable(model, started.model(), "model");
                createdAt = started.createdAt();
                requestId = stable(requestId, started.requestId(), "requestId");
                return List.of(base(started.sequence())
                        .responseId(responseId)
                        .conversationId(conversationId)
                        .model(model)
                        .createdAt(createdAt)
                        .metadata(requestMetadata())
                        .build());
            }
            if (event instanceof OpenAITransport.TextDelta text) {
                emittedTextIds.add(text.messageId());
                return List.of(base(text.sequence())
                        .role(Role.ASSISTANT)
                        .messageId(text.messageId())
                        .contents(List.of(textContent(
                                text.messageId(),
                                text.text(),
                                StateValue.bool(true).equals(text.metadata().get("openai.refusal")),
                                text.metadata())))
                        .build());
            }
            if (event instanceof OpenAITransport.ReasoningDelta reasoning) {
                emittedReasoningIds.add(reasoning.itemId());
                return List.of(base(reasoning.sequence())
                        .role(Role.ASSISTANT)
                        .messageId("reasoning:" + reasoning.itemId())
                        .contents(List.of(new ReasoningContent(
                                reasoning.itemId(),
                                reasoning.text(),
                                reasoning.protectedData(),
                                Map.of("openai.summary", StateValue.bool(reasoning.summary())))))
                        .build());
            }
            if (event instanceof OpenAITransport.FunctionCallStarted started) {
                CallState previous = calls.putIfAbsent(
                        started.outputIndex(), new CallState(started.itemId(), started.callId(), started.name()));
                if (previous != null) {
                    previous.require(started.itemId(), started.callId(), started.name());
                }
                return List.of();
            }
            if (event instanceof OpenAITransport.FunctionArgumentsDelta delta) {
                CallState call = calls.get(delta.outputIndex());
                if (call == null || !call.itemId.equals(delta.itemId())) {
                    throw new OpenAIProtocolException(
                            "OpenAI emitted orphan function argument fragments.", requestId, "orphan_tool_delta");
                }
                call.arguments.append(delta.delta());
                return List.of();
            }
            if (event instanceof OpenAITransport.FunctionArgumentsDone done) {
                CallState call = calls.computeIfAbsent(
                        done.outputIndex(), ignored -> new CallState(done.itemId(), done.callId(), done.name()));
                call.require(done.itemId(), done.callId(), done.name());
                if (call.completed != null) {
                    throw new OpenAIProtocolException(
                            "OpenAI emitted a duplicate terminal function call.", requestId, "duplicate_tool_call");
                }
                if (!call.arguments.isEmpty()) {
                    StateValue accumulated = OpenAIStateJson.read(call.arguments.toString());
                    if (!accumulated.equals(done.arguments())) {
                        throw new OpenAIProtocolException(
                                "OpenAI function argument deltas do not match the terminal arguments.",
                                requestId,
                                "tool_delta_mismatch");
                    }
                }
                call.completed = new OpenAITransport.FunctionCallOutput(
                        done.callId(), done.name(), done.arguments(), done.itemId(), "completed");
                return flushCompletedCalls(done.sequence());
            }
            if (event instanceof OpenAITransport.ImageDelta image) {
                return List.of(base(image.sequence())
                        .role(Role.ASSISTANT)
                        .messageId("image:" + image.sequence())
                        .contents(List.of(mapOutput(image.image())))
                        .build());
            }
            if (event instanceof OpenAITransport.ResponseFailed failed) {
                requestId = stable(requestId, failed.requestId(), "requestId");
                String safeRequestId = OpenAIProviderException.safeIdentifier(requestId);
                terminal = true;
                throw new OpenAIProviderException(
                        "OpenAI streaming response failed"
                                + (safeRequestId == null ? "." : " (request " + safeRequestId + ")."),
                        null,
                        requestId,
                        failed.errorCode());
            }
            if (event instanceof OpenAITransport.ResponseCompleted completed) {
                terminal = true;
                OpenAITransport.Response response = completed.response();
                requireSuccessfulOrContinuable(response);
                responseId = stable(responseId, response.responseId(), "responseId");
                conversationId = stable(conversationId, response.conversationId(), "conversationId");
                model = stable(model, response.model(), "model");
                createdAt = response.createdAt();
                requestId = stable(requestId, response.requestId(), "requestId");
                ArrayList<ChatResponseUpdate> updates = fallbackOutputs(completed.sequence(), response.outputs());
                ChatResponseUpdate.Builder terminalUpdate = base(completed.sequence())
                        .responseId(responseId)
                        .model(model)
                        .createdAt(createdAt)
                        .metadata(metadata(response));
                if (conversationId != null) {
                    terminalUpdate.conversationId(conversationId);
                }
                FinishReason finishReason = finishReason(response);
                if (finishReason != null) {
                    terminalUpdate.finishReason(finishReason);
                }
                UsageDetails usage = usage(response.usage());
                if (usage != null) {
                    terminalUpdate.usage(usage);
                }
                StateValue continuation = continuation(response);
                if (continuation != null) {
                    terminalUpdate.continuationToken(continuation);
                }
                updates.add(terminalUpdate.build());
                return List.copyOf(updates);
            }
            throw new OpenAIProtocolException(
                    "OpenAI emitted an unsupported streaming event.", requestId, "unsupported_event");
        }

        void requireTerminal() {
            if (!terminal) {
                throw new OpenAIProtocolException(
                        "OpenAI stream closed without a terminal response event.", requestId, "missing_terminal_event");
            }
            if (calls.values().stream().anyMatch(call -> !call.emitted && !emittedCallIds.contains(call.callId))) {
                throw new OpenAIProtocolException(
                        "OpenAI stream closed with orphan function argument fragments.",
                        requestId,
                        "orphan_tool_delta");
            }
        }

        private ArrayList<ChatResponseUpdate> fallbackOutputs(long sequence, List<OpenAITransport.OutputItem> outputs) {
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            for (OpenAITransport.OutputItem output : outputs) {
                if (output instanceof OpenAITransport.TextOutput text && emittedTextIds.add(text.messageId())) {
                    updates.add(base(sequence)
                            .role(Role.ASSISTANT)
                            .messageId(text.messageId())
                            .contents(List.of(mapOutput(text)))
                            .build());
                } else if (output instanceof OpenAITransport.ReasoningOutput reasoning
                        && emittedReasoningIds.add(reasoning.id())) {
                    updates.add(base(sequence)
                            .role(Role.ASSISTANT)
                            .messageId("reasoning:" + reasoning.id())
                            .contents(List.of(mapOutput(reasoning)))
                            .build());
                } else if (output instanceof OpenAITransport.FunctionCallOutput call
                        && emittedCallIds.add(call.callId())) {
                    updates.add(functionUpdate(sequence, call));
                } else if (output instanceof OpenAITransport.ImageOutput image) {
                    updates.add(base(sequence)
                            .role(Role.ASSISTANT)
                            .messageId("image:" + sequence + ':' + updates.size())
                            .contents(List.of(mapOutput(image)))
                            .build());
                }
            }
            return updates;
        }

        private ChatResponseUpdate functionUpdate(long sequence, OpenAITransport.FunctionCallOutput call) {
            return base(sequence)
                    .role(Role.ASSISTANT)
                    .messageId(call.providerItemId() == null ? "function:" + call.callId() : call.providerItemId())
                    .contents(List.of(mapOutput(call)))
                    .build();
        }

        private ChatResponseUpdate.Builder base(long sequence) {
            ChatResponseUpdate.Builder builder =
                    ChatResponseUpdate.builder().sequence(sequence).metadata(requestMetadata());
            if (responseId != null) {
                builder.responseId(responseId);
            }
            if (conversationId != null) {
                builder.conversationId(conversationId);
            }
            if (model != null) {
                builder.model(model);
            }
            if (createdAt != null) {
                builder.createdAt(createdAt);
            }
            return builder;
        }

        private Map<String, StateValue> requestMetadata() {
            return requestId == null ? Map.of() : Map.of("openai.requestId", StateValue.string(requestId));
        }

        private List<ChatResponseUpdate> flushCompletedCalls(long sequence) {
            ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
            for (CallState call : calls.values()) {
                if (call.emitted) {
                    continue;
                }
                if (call.completed == null) {
                    break;
                }
                if (!emittedCallIds.add(call.callId)) {
                    throw new OpenAIProtocolException(
                            "OpenAI emitted a duplicate terminal function call.", requestId, "duplicate_tool_call");
                }
                call.emitted = true;
                updates.add(functionUpdate(sequence, call.completed));
            }
            return List.copyOf(updates);
        }

        private String stable(String current, String incoming, String name) {
            if (incoming == null) {
                return current;
            }
            if (current != null && !current.equals(incoming)) {
                throw new OpenAIProtocolException(
                        "OpenAI changed " + name + " within one stream.", requestId, "unstable_identifier");
            }
            return incoming;
        }

        private final class CallState {
            private final String itemId;

            private final String callId;

            private final String name;

            private final StringBuilder arguments = new StringBuilder();

            private boolean emitted;

            private OpenAITransport.FunctionCallOutput completed;

            private CallState(String itemId, String callId, String name) {
                this.itemId = itemId;
                this.callId = callId;
                this.name = name;
            }

            private void require(String nextItemId, String nextCallId, String nextName) {
                if (!itemId.equals(nextItemId) || !callId.equals(nextCallId) || !name.equals(nextName)) {
                    throw new OpenAIProtocolException(
                            "OpenAI changed function-call correlation within one stream.",
                            requestId,
                            "unstable_tool_call");
                }
            }
        }
    }
}
