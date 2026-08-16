// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Provides bounded JSON parsing and typed response decoding for structured output. */
public final class StructuredOutputs {
    private StructuredOutputs() {}

    /**
     * Parses one complete JSON document using the framework serialization limits.
     *
     * @param json structured response JSON
     * @return parsed framework-owned value
     * @throws SerializationException when JSON is malformed or exceeds a limit
     */
    public static StateValue parseJson(String json) {
        return parseJson(json, SerializationLimits.defaults());
    }

    /**
     * Parses one complete JSON document using explicit limits.
     *
     * @param json structured response JSON
     * @param limits mandatory parser limits
     * @return parsed framework-owned value
     * @throws SerializationException when JSON is malformed or exceeds a limit
     */
    public static StateValue parseJson(String json, SerializationLimits limits) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(limits, "limits");
        if (limits.maxDocumentBytes() > Integer.MAX_VALUE) {
            throw new ValidationException("Structured output maxDocumentBytes must not exceed Integer.MAX_VALUE.");
        }
        int maxDocumentBytes = Math.toIntExact(limits.maxDocumentBytes());
        StrictJsonCodec codec = new StrictJsonCodec(
                maxDocumentBytes,
                maxDocumentBytes,
                limits.maxNestingDepth(),
                limits.maxStringLength(),
                limits.maxNumericTokenLength(),
                limits.maxCollectionEntries());
        return codec.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes the last non-empty assistant text as structured JSON while preserving response
     * metadata.
     *
     * <p>An assistant response without non-empty text produces a response whose value is
     * {@code null}. Malformed JSON and decoder failures are surfaced without including model output
     * in the exception message.
     *
     * @param response source response
     * @param decoder application decoder
     * @param <T> decoded response type
     * @return response with the decoded value
     */
    public static <T> AgentResponse<T> decode(AgentResponse<?> response, StructuredOutputDecoder<? extends T> decoder) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(decoder, "decoder");
        String text = lastNonEmptyAssistantText(response.messages());
        T value = null;
        if (!text.isEmpty()) {
            StateValue parsed;
            try {
                parsed = parseJson(text);
            } catch (RuntimeException failure) {
                throw new StructuredOutputException("Agent structured output was not valid bounded JSON.", failure);
            }
            try {
                value = decoder.decode(parsed);
            } catch (StructuredOutputException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new StructuredOutputException("Agent structured output could not be decoded.", failure);
            }
        }
        return new AgentResponse<>(
                response.messages(),
                response.responseId(),
                response.agentId(),
                response.createdAt(),
                response.finishReason(),
                response.usage(),
                value,
                response.continuationToken(),
                response.metadata(),
                response.updateSequences());
    }

    private static String lastNonEmptyAssistantText(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (!message.role().equals(Role.ASSISTANT)) {
                continue;
            }
            String text = message.contents().stream()
                    .filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast)
                    .map(TextContent::text)
                    .reduce("", String::concat);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
