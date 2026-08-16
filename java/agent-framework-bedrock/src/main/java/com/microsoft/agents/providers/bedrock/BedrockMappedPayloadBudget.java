// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.MetadataContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.UsageContent;
import com.microsoft.agents.core.UsageDetails;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Enforces deterministic bounds over SDK-decoded framework response fields.
 */
final class BedrockMappedPayloadBudget {
    private static final long STRUCTURAL_OVERHEAD = 32;

    private final long maxResponseBytes;

    private final long maxEventBytes;

    private long consumed;

    BedrockMappedPayloadBudget(long maxResponseBytes, long maxEventBytes) {
        this.maxResponseBytes = maxResponseBytes;
        this.maxEventBytes = maxEventBytes;
    }

    synchronized void acceptResponse(ChatResponse response) {
        if (responseSize(response) > maxResponseBytes) {
            throw failure("mapped_response_too_large");
        }
    }

    synchronized void acceptEvent(ChatResponseUpdate update) {
        long eventBytes = updateSize(update);
        if (eventBytes > maxEventBytes) {
            throw failure("mapped_event_too_large");
        }
        consumed = add(consumed, eventBytes);
        if (consumed > maxResponseBytes) {
            throw failure("mapped_response_too_large");
        }
    }

    private static long responseSize(ChatResponse response) {
        long size = STRUCTURAL_OVERHEAD;
        size = add(size, messages(response.messages()));
        size = add(size, string(response.responseId()));
        size = add(size, string(response.conversationId()));
        size = add(size, string(response.model()));
        size = add(
                size,
                response.createdAt() == null ? 0 : string(response.createdAt().toString()));
        size = add(
                size,
                response.finishReason() == null
                        ? 0
                        : string(response.finishReason().value()));
        size = add(size, usage(response.usage()));
        size = add(size, state(response.continuationToken()));
        size = add(size, stateMap(response.metadata()));
        size = add(size, Math.multiplyExact((long) response.updateSequences().size(), Long.BYTES));
        return size;
    }

    private static long updateSize(ChatResponseUpdate update) {
        long size = STRUCTURAL_OVERHEAD + Long.BYTES;
        size = add(size, contents(update.contents()));
        size = add(size, update.role() == null ? 0 : string(update.role().value()));
        size = add(size, string(update.authorName()));
        size = add(size, string(update.responseId()));
        size = add(size, string(update.messageId()));
        size = add(size, string(update.conversationId()));
        size = add(size, string(update.model()));
        size = add(
                size, update.createdAt() == null ? 0 : string(update.createdAt().toString()));
        size = add(
                size,
                update.finishReason() == null ? 0 : string(update.finishReason().value()));
        size = add(size, usage(update.usage()));
        size = add(size, state(update.continuationToken()));
        return add(size, stateMap(update.metadata()));
    }

    private static long messages(List<Message> messages) {
        long size = STRUCTURAL_OVERHEAD;
        for (Message message : messages) {
            size = add(size, STRUCTURAL_OVERHEAD);
            size = add(size, string(message.role().value()));
            size = add(size, string(message.authorName()));
            size = add(size, string(message.messageId()));
            size = add(size, contents(message.contents()));
            size = add(size, stateMap(message.metadata()));
        }
        return size;
    }

    private static long contents(List<? extends Content> contents) {
        long size = STRUCTURAL_OVERHEAD;
        for (Content content : contents) {
            size = add(size, STRUCTURAL_OVERHEAD + string(content.kind()));
            size = add(
                    size,
                    switch (content) {
                        case TextContent text -> string(text.text());
                        case ReasoningContent reasoning ->
                            add(
                                    add(string(reasoning.id()), string(reasoning.text())),
                                    string(reasoning.protectedData()));
                        case FunctionCallContent call ->
                            add(add(string(call.callId()), string(call.name())), state(call.arguments()));
                        case FunctionResultContent result ->
                            add(
                                    add(add(string(result.callId()), state(result.result())), contents(result.items())),
                                    string(result.error()));
                        case MetadataContent metadata -> stateMap(metadata.values());
                        case ErrorContent error ->
                            add(add(string(error.message()), string(error.errorCode())), string(error.details()));
                        case DataContent data -> add(data.data().length, string(data.mediaType()));
                        case UriContent uri -> add(string(uri.uri().toString()), string(uri.mediaType()));
                        case UsageContent usage -> usage(usage.usage());
                    });
            size = add(size, stateMap(content.metadata()));
        }
        return size;
    }

    private static long usage(UsageDetails usage) {
        return usage == null ? 0 : stateMap(usage.values());
    }

    private static long stateMap(Map<String, StateValue> values) {
        long size = STRUCTURAL_OVERHEAD;
        for (Map.Entry<String, StateValue> entry : values.entrySet()) {
            size = add(size, string(entry.getKey()));
            size = add(size, state(entry.getValue()));
        }
        return size;
    }

    private static long state(StateValue value) {
        if (value == null) {
            return 0;
        }
        return switch (value) {
            case StateValue.NullValue _ -> 4;
            case StateValue.BooleanValue bool -> bool.value() ? 4 : 5;
            case StateValue.NumberValue number -> string(number.value().toString());
            case StateValue.StringValue string -> string(string.value());
            case StateValue.ArrayValue array -> {
                long size = STRUCTURAL_OVERHEAD;
                for (StateValue item : array.values()) {
                    size = add(size, state(item));
                }
                yield size;
            }
            case StateValue.ObjectValue object -> stateMap(object.values());
        };
    }

    private static long string(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static BedrockProviderException failure(String kind) {
        return new BedrockProviderException(kind, null, null, null);
    }
}
