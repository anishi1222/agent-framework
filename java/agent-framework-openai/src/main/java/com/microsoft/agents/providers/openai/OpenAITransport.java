// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned boundary used to execute OpenAI Responses requests.
 *
 * <p>The production implementation adapts these values to the official OpenAI Java SDK. Supplying a
 * transport to {@link OpenAIChatClient.Builder#transport(OpenAITransport)} enables deterministic
 * offline tests and custom network stacks without exposing SDK types.
 */
public interface OpenAITransport extends AutoCloseable {
    /**
     * Executes one finite response request.
     *
     * @param request immutable OpenAI request
     * @param cancellation run cancellation
     * @return response stage
     */
    CompletionStage<Response> completeAsync(Request request, RunCancellation cancellation);

    /**
     * Executes one streaming response request.
     *
     * @param request immutable OpenAI request
     * @param cancellation run cancellation
     * @return cold stream-event publisher
     */
    Flow.Publisher<StreamEvent> completeStreaming(Request request, RunCancellation cancellation);

    /** Releases caller-owned transport resources by default as a no-op. */
    @Override
    default void close() {}

    /**
     * Represents one request after provider-neutral model mapping.
     *
     * @param model model identifier
     * @param input ordered input items
     * @param instructions optional native instructions
     * @param temperature optional sampling temperature
     * @param topP optional nucleus sampling
     * @param maxOutputTokens optional output-token bound
     * @param tools function declarations
     * @param toolChoice optional tool selection
     * @param parallelToolCalls optional parallel-call preference
     * @param user optional end-user identifier
     * @param store optional service-side storage preference
     * @param previousResponseId optional previous response identifier
     * @param conversationId optional conversation identifier
     * @param metadata OpenAI string metadata
     * @param structuredOutput optional JSON Schema response contract
     * @param responseOptions provider-specific response options
     */
    record Request(
            String model,
            List<InputItem> input,
            String instructions,
            Double temperature,
            Double topP,
            Long maxOutputTokens,
            List<FunctionTool> tools,
            ToolSelection toolChoice,
            Boolean parallelToolCalls,
            String user,
            Boolean store,
            String previousResponseId,
            String conversationId,
            Map<String, String> metadata,
            StructuredOutputOptions structuredOutput,
            OpenAIResponseOptions responseOptions) {
        /** Creates and defensively copies one request. */
        public Request {
            model = required(model, "model");
            input = copy(input, "input");
            if (input.isEmpty()) {
                throw new IllegalArgumentException("input must not be empty.");
            }
            instructions = optional(instructions, "instructions");
            tools = copy(tools, "tools");
            user = optional(user, "user");
            previousResponseId = optional(previousResponseId, "previousResponseId");
            conversationId = optional(conversationId, "conversationId");
            if (previousResponseId != null && conversationId != null) {
                throw new IllegalArgumentException("previousResponseId and conversationId are mutually exclusive.");
            }
            metadata = copyMap(metadata, "metadata");
            responseOptions = Objects.requireNonNull(responseOptions, "responseOptions");
        }
    }

    /** Identifies an OpenAI Responses message role. */
    enum InputRole {
        /** System instruction. */
        SYSTEM,
        /** Developer instruction. */
        DEVELOPER,
        /** User input. */
        USER,
        /** Prior assistant output. */
        ASSISTANT
    }

    /** Identifies provider tool-selection behavior. */
    enum ToolSelection {
        /** The model may select a tool. */
        AUTO,
        /** The model must select at least one tool. */
        REQUIRED,
        /** The model must not select a tool. */
        NONE
    }

    /** Identifies image detail selection. */
    enum ImageDetail {
        /** Lets OpenAI select detail. */
        AUTO,
        /** Uses low detail. */
        LOW,
        /** Uses high detail. */
        HIGH,
        /** Preserves original detail when supported. */
        ORIGINAL
    }

    /** Represents one mapped Responses input item. */
    sealed interface InputItem permits MessageInput, FunctionCallInput, FunctionResultInput, ReasoningInput {}

    /**
     * Represents one message input.
     *
     * @param role message role
     * @param contents ordered content
     */
    record MessageInput(InputRole role, List<InputContent> contents) implements InputItem {
        /** Creates and defensively copies a message input. */
        public MessageInput {
            role = Objects.requireNonNull(role, "role");
            contents = copy(contents, "contents");
            if (contents.isEmpty()) {
                throw new IllegalArgumentException("contents must not be empty.");
            }
        }
    }

    /**
     * Represents one prior function call.
     *
     * @param callId call correlation identifier
     * @param name function name
     * @param arguments JSON-shaped arguments
     * @param providerItemId optional provider item identifier
     */
    record FunctionCallInput(String callId, String name, StateValue arguments, String providerItemId)
            implements InputItem {
        /** Creates a validated function-call input. */
        public FunctionCallInput {
            callId = required(callId, "callId");
            name = required(name, "name");
            arguments = Objects.requireNonNull(arguments, "arguments");
            providerItemId = optional(providerItemId, "providerItemId");
        }
    }

    /**
     * Represents one function result.
     *
     * @param callId call correlation identifier
     * @param result JSON-shaped result
     * @param items optional rich result items
     * @param error optional sanitized tool error
     */
    record FunctionResultInput(String callId, StateValue result, List<InputContent> items, String error)
            implements InputItem {
        /** Creates and defensively copies a function result. */
        public FunctionResultInput {
            callId = required(callId, "callId");
            result = Objects.requireNonNull(result, "result");
            items = copy(items, "items");
            error = optional(error, "error");
        }
    }

    /**
     * Represents replayable reasoning.
     *
     * @param id reasoning item identifier
     * @param text optional visible reasoning text
     * @param protectedData optional encrypted reasoning data
     */
    record ReasoningInput(String id, String text, String protectedData) implements InputItem {
        /** Creates a validated reasoning input. */
        public ReasoningInput {
            id = required(id, "id");
            if (text == null && protectedData == null) {
                throw new IllegalArgumentException("ReasoningInput requires text or protectedData.");
            }
        }
    }

    /** Represents message or rich function-result content. */
    sealed interface InputContent permits TextInput, ImageInput, FileInput {}

    /**
     * Represents text input.
     *
     * @param text text value
     */
    record TextInput(String text) implements InputContent {
        /** Creates text input. */
        public TextInput {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Represents image input.
     *
     * @param uri absolute image or data URI
     * @param mediaType image media type
     * @param detail image detail
     */
    record ImageInput(URI uri, String mediaType, ImageDetail detail) implements InputContent {
        /** Creates image input. */
        public ImageInput {
            uri = absolute(uri, "uri");
            mediaType = required(mediaType, "mediaType");
            if (!mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                throw new IllegalArgumentException("mediaType must identify an image.");
            }
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    /**
     * Represents file input.
     *
     * @param uri absolute file or data URI
     * @param mediaType application media type
     * @param filename optional filename
     */
    record FileInput(URI uri, String mediaType, String filename) implements InputContent {
        /** Creates file input. */
        public FileInput {
            uri = absolute(uri, "uri");
            mediaType = required(mediaType, "mediaType");
            filename = optional(filename, "filename");
        }
    }

    /**
     * Represents one OpenAI function declaration.
     *
     * @param name tool name
     * @param description tool description
     * @param inputSchema JSON Schema object
     */
    record FunctionTool(String name, String description, StateValue.ObjectValue inputSchema) {
        /** Creates a validated function declaration. */
        public FunctionTool {
            name = required(name, "name");
            description = Objects.requireNonNull(description, "description");
            inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        }
    }

    /** Identifies a provider response state. */
    enum ResponseStatus {
        /** Response completed successfully. */
        COMPLETED,
        /** Response ended before normal completion. */
        INCOMPLETE,
        /** Response failed. */
        FAILED,
        /** Response was cancelled. */
        CANCELLED,
        /** Response is still running. */
        IN_PROGRESS,
        /** Response is queued. */
        QUEUED
    }

    /**
     * Represents one finite provider response.
     *
     * @param responseId response identifier
     * @param conversationId optional conversation identifier
     * @param model model identifier
     * @param createdAt creation time
     * @param status response status
     * @param outputs ordered output items
     * @param usage optional usage
     * @param metadata response metadata
     * @param requestId optional HTTP request identifier
     * @param incompleteReason optional incomplete reason
     * @param errorCode optional provider error code
     */
    record Response(
            String responseId,
            String conversationId,
            String model,
            Instant createdAt,
            ResponseStatus status,
            List<OutputItem> outputs,
            Usage usage,
            Map<String, StateValue> metadata,
            String requestId,
            String incompleteReason,
            String errorCode) {
        /** Creates and defensively copies a response. */
        public Response {
            responseId = required(responseId, "responseId");
            conversationId = optional(conversationId, "conversationId");
            model = required(model, "model");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            status = Objects.requireNonNull(status, "status");
            outputs = copy(outputs, "outputs");
            metadata = copyStateMap(metadata, "metadata");
            requestId = optional(requestId, "requestId");
            incompleteReason = optional(incompleteReason, "incompleteReason");
            errorCode = optional(errorCode, "errorCode");
        }
    }

    /** Represents one provider response output item. */
    sealed interface OutputItem permits TextOutput, ReasoningOutput, FunctionCallOutput, ImageOutput {}

    /**
     * Represents assistant text or refusal output.
     *
     * @param messageId stable message identifier
     * @param text text
     * @param refusal whether the output is a refusal
     * @param metadata content metadata
     */
    record TextOutput(String messageId, String text, boolean refusal, Map<String, StateValue> metadata)
            implements OutputItem {
        /** Creates text output. */
        public TextOutput {
            messageId = required(messageId, "messageId");
            Objects.requireNonNull(text, "text");
            metadata = copyStateMap(metadata, "metadata");
        }
    }

    /**
     * Represents reasoning output.
     *
     * @param id reasoning item identifier
     * @param text optional reasoning text
     * @param protectedData optional encrypted reasoning
     * @param summary whether text is a summary
     */
    record ReasoningOutput(String id, String text, String protectedData, boolean summary) implements OutputItem {
        /** Creates reasoning output. */
        public ReasoningOutput {
            id = required(id, "id");
            if (text == null && protectedData == null) {
                throw new IllegalArgumentException("ReasoningOutput requires text or protectedData.");
            }
        }
    }

    /**
     * Represents one requested function call.
     *
     * @param callId call correlation identifier
     * @param name function name
     * @param arguments JSON-shaped arguments
     * @param providerItemId optional provider item identifier
     * @param status optional provider item status
     */
    record FunctionCallOutput(String callId, String name, StateValue arguments, String providerItemId, String status)
            implements OutputItem {
        /** Creates function-call output. */
        public FunctionCallOutput {
            callId = required(callId, "callId");
            name = required(name, "name");
            arguments = Objects.requireNonNull(arguments, "arguments");
            providerItemId = optional(providerItemId, "providerItemId");
            status = optional(status, "status");
        }
    }

    /**
     * Represents image output.
     *
     * @param uri absolute image or data URI
     * @param mediaType image media type
     */
    record ImageOutput(URI uri, String mediaType) implements OutputItem {
        /** Creates image output. */
        public ImageOutput {
            uri = absolute(uri, "uri");
            mediaType = required(mediaType, "mediaType");
        }
    }

    /**
     * Represents token usage.
     *
     * @param inputTokens input tokens
     * @param outputTokens output tokens
     * @param totalTokens total tokens
     * @param cachedInputTokens optional cached input tokens
     * @param reasoningOutputTokens optional reasoning output tokens
     */
    record Usage(
            long inputTokens, long outputTokens, long totalTokens, Long cachedInputTokens, Long reasoningOutputTokens) {
        /** Creates non-negative usage. */
        public Usage {
            if (inputTokens < 0
                    || outputTokens < 0
                    || totalTokens < 0
                    || cachedInputTokens != null && cachedInputTokens < 0
                    || reasoningOutputTokens != null && reasoningOutputTokens < 0) {
                throw new IllegalArgumentException("Usage values must not be negative.");
            }
        }
    }

    /** Represents one meaningful OpenAI streaming event. */
    sealed interface StreamEvent
            permits ResponseStarted,
                    TextDelta,
                    ReasoningDelta,
                    FunctionCallStarted,
                    FunctionArgumentsDelta,
                    FunctionArgumentsDone,
                    ImageDelta,
                    ResponseCompleted,
                    ResponseFailed {}

    /**
     * Announces response identity and state.
     *
     * @param sequence event sequence
     * @param responseId response identifier
     * @param conversationId optional conversation identifier
     * @param model model identifier
     * @param createdAt creation time
     * @param requestId optional HTTP request identifier
     * @param status response state
     */
    record ResponseStarted(
            long sequence,
            String responseId,
            String conversationId,
            String model,
            Instant createdAt,
            String requestId,
            ResponseStatus status)
            implements StreamEvent {
        /** Creates a validated response-start event. */
        public ResponseStarted {
            nonNegative(sequence, "sequence");
            responseId = required(responseId, "responseId");
            conversationId = optional(conversationId, "conversationId");
            model = required(model, "model");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            requestId = optional(requestId, "requestId");
            status = Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Carries one text delta.
     *
     * @param sequence event sequence
     * @param messageId message identifier
     * @param text text delta
     * @param metadata delta metadata
     */
    record TextDelta(long sequence, String messageId, String text, Map<String, StateValue> metadata)
            implements StreamEvent {
        /** Creates a text delta. */
        public TextDelta {
            nonNegative(sequence, "sequence");
            messageId = required(messageId, "messageId");
            Objects.requireNonNull(text, "text");
            metadata = copyStateMap(metadata, "metadata");
        }
    }

    /**
     * Carries one reasoning delta.
     *
     * @param sequence event sequence
     * @param itemId reasoning item identifier
     * @param text optional text delta
     * @param protectedData optional encrypted reasoning
     * @param summary whether text is a summary
     */
    record ReasoningDelta(long sequence, String itemId, String text, String protectedData, boolean summary)
            implements StreamEvent {
        /** Creates a reasoning delta. */
        public ReasoningDelta {
            nonNegative(sequence, "sequence");
            itemId = required(itemId, "itemId");
            if (text == null && protectedData == null) {
                throw new IllegalArgumentException("ReasoningDelta requires text or protectedData.");
            }
        }
    }

    /**
     * Announces a streaming function call.
     *
     * @param sequence event sequence
     * @param outputIndex provider output index
     * @param itemId provider item identifier
     * @param callId call correlation identifier
     * @param name function name
     */
    record FunctionCallStarted(long sequence, long outputIndex, String itemId, String callId, String name)
            implements StreamEvent {
        /** Creates a function-call start event. */
        public FunctionCallStarted {
            nonNegative(sequence, "sequence");
            nonNegative(outputIndex, "outputIndex");
            itemId = required(itemId, "itemId");
            callId = required(callId, "callId");
            name = required(name, "name");
        }
    }

    /**
     * Carries a function-arguments fragment.
     *
     * @param sequence event sequence
     * @param outputIndex provider output index
     * @param itemId provider item identifier
     * @param delta JSON fragment
     */
    record FunctionArgumentsDelta(long sequence, long outputIndex, String itemId, String delta) implements StreamEvent {
        /** Creates an arguments delta. */
        public FunctionArgumentsDelta {
            nonNegative(sequence, "sequence");
            nonNegative(outputIndex, "outputIndex");
            itemId = required(itemId, "itemId");
            Objects.requireNonNull(delta, "delta");
        }
    }

    /**
     * Finalizes one function call.
     *
     * @param sequence event sequence
     * @param outputIndex provider output index
     * @param itemId provider item identifier
     * @param callId call correlation identifier
     * @param name function name
     * @param arguments complete JSON arguments
     */
    record FunctionArgumentsDone(
            long sequence, long outputIndex, String itemId, String callId, String name, StateValue arguments)
            implements StreamEvent {
        /** Creates a completed function-arguments event. */
        public FunctionArgumentsDone {
            nonNegative(sequence, "sequence");
            nonNegative(outputIndex, "outputIndex");
            itemId = required(itemId, "itemId");
            callId = required(callId, "callId");
            name = required(name, "name");
            arguments = Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * Carries image output.
     *
     * @param sequence event sequence
     * @param image image output
     */
    record ImageDelta(long sequence, ImageOutput image) implements StreamEvent {
        /** Creates an image event. */
        public ImageDelta {
            nonNegative(sequence, "sequence");
            image = Objects.requireNonNull(image, "image");
        }
    }

    /**
     * Terminates a stream with a provider response.
     *
     * @param sequence event sequence
     * @param response terminal response
     */
    record ResponseCompleted(long sequence, Response response) implements StreamEvent {
        /** Creates a terminal response event. */
        public ResponseCompleted {
            nonNegative(sequence, "sequence");
            response = Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Terminates a stream with a provider failure.
     *
     * @param sequence event sequence
     * @param responseId optional response identifier
     * @param requestId optional request identifier
     * @param errorCode optional provider error code
     */
    record ResponseFailed(long sequence, String responseId, String requestId, String errorCode) implements StreamEvent {
        /** Creates a terminal failure event. */
        public ResponseFailed {
            nonNegative(sequence, "sequence");
            responseId = optional(responseId, "responseId");
            requestId = optional(requestId, "requestId");
            errorCode = optional(errorCode, "errorCode");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optional(String value, String name) {
        return value == null ? null : required(value, name);
    }

    private static URI absolute(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute.");
        }
        return value;
    }

    private static void nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }

    private static <T> List<T> copy(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> copyMap(Map<String, String> values, String name) {
        Objects.requireNonNull(values, name);
        java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(required(key, name + " key"), Objects.requireNonNull(value, name)));
        return Map.copyOf(copy);
    }

    private static Map<String, StateValue> copyStateMap(Map<String, StateValue> values, String name) {
        Objects.requireNonNull(values, name);
        java.util.LinkedHashMap<String, StateValue> copy = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(required(key, name + " key"), Objects.requireNonNull(value, name)));
        return Map.copyOf(copy);
    }
}
