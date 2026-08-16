// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.StateValue;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAISdkResponseMapper {
    private OpenAISdkResponseMapper() {}

    static OpenAITransport.Response map(Response response, String requestId) {
        return map(response, requestId, null);
    }

    static OpenAITransport.Response map(
            Response response, String requestId, OpenAIImageOutputFormat requestedImageOutputFormat) {
        ArrayList<OpenAITransport.OutputItem> outputs = new ArrayList<>();
        response.output().forEach(item -> outputs.addAll(mapOutput(item, requestedImageOutputFormat)));
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        response.metadata()
                .ifPresent(value ->
                        value._additionalProperties().forEach((key, item) -> metadata.put(key, fromJson(item))));
        response.serviceTier()
                .ifPresent(tier -> metadata.put("openai.serviceTier", StateValue.string(tier.asString())));
        return new OpenAITransport.Response(
                response.id(),
                response.conversation().map(Response.Conversation::id).orElse(null),
                response.model().asString(),
                instant(response.createdAt()),
                mapStatus(response.status().orElse(ResponseStatus.COMPLETED)),
                outputs,
                response.usage().map(OpenAISdkResponseMapper::mapUsage).orElse(null),
                metadata,
                requestId,
                response.incompleteDetails()
                        .flatMap(Response.IncompleteDetails::reason)
                        .map(Response.IncompleteDetails.Reason::asString)
                        .orElse(null),
                response.error()
                        .map(ResponseError::code)
                        .map(ResponseError.Code::asString)
                        .orElse(null));
    }

    private static List<OpenAITransport.OutputItem> mapOutput(
            ResponseOutputItem output, OpenAIImageOutputFormat requestedImageOutputFormat) {
        if (output.isMessage()) {
            ResponseOutputMessage message = output.asMessage();
            ArrayList<OpenAITransport.OutputItem> items = new ArrayList<>();
            for (ResponseOutputMessage.Content content : message.content()) {
                if (content.isOutputText()) {
                    items.add(new OpenAITransport.TextOutput(
                            message.id(), content.asOutputText().text(), false, Map.of()));
                } else if (content.isRefusal()) {
                    items.add(new OpenAITransport.TextOutput(
                            message.id(), content.asRefusal().refusal(), true, Map.of()));
                } else {
                    throw unsupportedOutput("message content");
                }
            }
            return List.copyOf(items);
        }
        if (output.isFunctionCall()) {
            ResponseFunctionToolCall call = output.asFunctionCall();
            return List.of(new OpenAITransport.FunctionCallOutput(
                    call.callId(),
                    call.name(),
                    OpenAIStateJson.read(call.arguments()),
                    call.id().orElse(null),
                    call.status().map(ResponseFunctionToolCall.Status::asString).orElse(null)));
        }
        if (output.isReasoning()) {
            ResponseReasoningItem reasoning = output.asReasoning();
            ArrayList<OpenAITransport.OutputItem> items = new ArrayList<>();
            String encrypted = reasoning.encryptedContent().orElse(null);
            for (ResponseReasoningItem.Summary summary : reasoning.summary()) {
                items.add(new OpenAITransport.ReasoningOutput(reasoning.id(), summary.text(), encrypted, true));
            }
            reasoning
                    .content()
                    .ifPresent(contents -> contents.forEach(content -> items.add(
                            new OpenAITransport.ReasoningOutput(reasoning.id(), content.text(), encrypted, false))));
            if (items.isEmpty() && encrypted != null) {
                items.add(new OpenAITransport.ReasoningOutput(reasoning.id(), null, encrypted, false));
            }
            return List.copyOf(items);
        }
        if (output.isImageGenerationCall()) {
            ResponseOutputItem.ImageGenerationCall image = output.asImageGenerationCall();
            return image.result()
                    .map(value -> List.<OpenAITransport.OutputItem>of(imageOutput(value, requestedImageOutputFormat)))
                    .orElseGet(List::of);
        }
        throw unsupportedOutput("item");
    }

    private static OpenAIProtocolException unsupportedOutput(String kind) {
        return new OpenAIProtocolException(
                "OpenAI returned an unsupported response output " + kind + ".", null, "unsupported_output");
    }

    private static OpenAITransport.Usage mapUsage(ResponseUsage usage) {
        return new OpenAITransport.Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                usage.inputTokensDetails().cachedTokens(),
                usage.outputTokensDetails().reasoningTokens());
    }

    private static OpenAITransport.ResponseStatus mapStatus(ResponseStatus status) {
        return switch (status.value()) {
            case COMPLETED -> OpenAITransport.ResponseStatus.COMPLETED;
            case INCOMPLETE -> OpenAITransport.ResponseStatus.INCOMPLETE;
            case FAILED -> OpenAITransport.ResponseStatus.FAILED;
            case CANCELLED -> OpenAITransport.ResponseStatus.CANCELLED;
            case IN_PROGRESS -> OpenAITransport.ResponseStatus.IN_PROGRESS;
            case QUEUED -> OpenAITransport.ResponseStatus.QUEUED;
            case _UNKNOWN ->
                throw new OpenAIProtocolException(
                        "OpenAI returned an unknown response status.", null, "unknown_status");
        };
    }

    private static Instant instant(double epochSeconds) {
        if (!Double.isFinite(epochSeconds) || epochSeconds < 0) {
            throw new OpenAIProtocolException("OpenAI returned an invalid creation time.", null, "invalid_created_at");
        }
        BigDecimal value = BigDecimal.valueOf(epochSeconds);
        long seconds = value.longValue();
        int nanos = value.subtract(BigDecimal.valueOf(seconds))
                .movePointRight(9)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        if (nanos == 1_000_000_000) {
            seconds++;
            nanos = 0;
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static StateValue fromJson(JsonValue value) {
        return value.accept(new JsonValue.Visitor<>() {
            @Override
            public StateValue visitMissing() {
                return StateValue.nullValue();
            }

            @Override
            public StateValue visitNull() {
                return StateValue.nullValue();
            }

            @Override
            public StateValue visitBoolean(boolean item) {
                return StateValue.bool(item);
            }

            @Override
            public StateValue visitNumber(Number item) {
                return StateValue.number(new BigDecimal(item.toString()));
            }

            @Override
            public StateValue visitString(String item) {
                return StateValue.string(item);
            }

            @Override
            public StateValue visitArray(List<? extends JsonValue> values) {
                return StateValue.array(
                        values.stream().map(OpenAISdkResponseMapper::fromJson).toList());
            }

            @Override
            public StateValue visitObject(Map<String, ? extends JsonValue> values) {
                LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
                values.forEach((key, item) -> result.put(key, fromJson(item)));
                return StateValue.object(result);
            }
        });
    }

    static final class StreamEventMapper {
        private final Map<Long, CallDescriptor> calls = new HashMap<>();

        private final String requestId;

        private final OpenAIImageOutputFormat requestedImageOutputFormat;

        private boolean terminal;

        StreamEventMapper() {
            this(null, null);
        }

        StreamEventMapper(String requestId, OpenAIImageOutputFormat requestedImageOutputFormat) {
            this.requestId = OpenAIProviderException.safeIdentifier(requestId);
            this.requestedImageOutputFormat = requestedImageOutputFormat;
        }

        List<OpenAITransport.StreamEvent> map(ResponseStreamEvent event) {
            if (terminal) {
                return List.of();
            }
            if (event.isCreated()) {
                var created = event.asCreated();
                return List.of(started(created.sequenceNumber(), created.response()));
            }
            if (event.isQueued()) {
                var queued = event.asQueued();
                return List.of(started(queued.sequenceNumber(), queued.response()));
            }
            if (event.isInProgress()) {
                var inProgress = event.asInProgress();
                return List.of(started(inProgress.sequenceNumber(), inProgress.response()));
            }
            if (event.isOutputTextDelta()) {
                var delta = event.asOutputTextDelta();
                return List.of(
                        new OpenAITransport.TextDelta(delta.sequenceNumber(), delta.itemId(), delta.delta(), Map.of()));
            }
            if (event.isRefusalDelta()) {
                var delta = event.asRefusalDelta();
                return List.of(new OpenAITransport.TextDelta(
                        delta.sequenceNumber(),
                        delta.itemId(),
                        delta.delta(),
                        Map.of("openai.refusal", StateValue.bool(true))));
            }
            if (event.isReasoningSummaryTextDelta()) {
                var delta = event.asReasoningSummaryTextDelta();
                return List.of(new OpenAITransport.ReasoningDelta(
                        delta.sequenceNumber(), delta.itemId(), delta.delta(), null, true));
            }
            if (event.isReasoningTextDelta()) {
                var delta = event.asReasoningTextDelta();
                return List.of(new OpenAITransport.ReasoningDelta(
                        delta.sequenceNumber(), delta.itemId(), delta.delta(), null, false));
            }
            if (event.isOutputItemAdded()) {
                var added = event.asOutputItemAdded();
                ResponseOutputItem item = added.item();
                if (item.isFunctionCall()) {
                    ResponseFunctionToolCall call = item.asFunctionCall();
                    CallDescriptor descriptor = new CallDescriptor(call.id().orElse(null), call.callId(), call.name());
                    calls.put(added.outputIndex(), descriptor);
                    if (descriptor.itemId() == null) {
                        return List.of();
                    }
                    return List.of(new OpenAITransport.FunctionCallStarted(
                            added.sequenceNumber(),
                            added.outputIndex(),
                            descriptor.itemId(),
                            descriptor.callId(),
                            descriptor.name()));
                }
                if (item.isMessage() || item.isReasoning() || item.isImageGenerationCall()) {
                    return List.of();
                }
                throw unsupportedOutput("stream item");
            }
            if (event.isFunctionCallArgumentsDelta()) {
                var delta = event.asFunctionCallArgumentsDelta();
                CallDescriptor call = requireCall(delta.outputIndex(), delta.itemId(), null);
                OpenAITransport.FunctionArgumentsDelta mapped = new OpenAITransport.FunctionArgumentsDelta(
                        delta.sequenceNumber(), delta.outputIndex(), delta.itemId(), delta.delta());
                if (call.itemId() == null) {
                    CallDescriptor identified = new CallDescriptor(delta.itemId(), call.callId(), call.name());
                    calls.put(delta.outputIndex(), identified);
                    return List.of(
                            new OpenAITransport.FunctionCallStarted(
                                    delta.sequenceNumber(),
                                    delta.outputIndex(),
                                    delta.itemId(),
                                    identified.callId(),
                                    identified.name()),
                            mapped);
                }
                return List.of(mapped);
            }
            if (event.isFunctionCallArgumentsDone()) {
                var done = event.asFunctionCallArgumentsDone();
                CallDescriptor call = requireCall(done.outputIndex(), done.itemId(), done.name());
                OpenAITransport.FunctionArgumentsDone mapped = new OpenAITransport.FunctionArgumentsDone(
                        done.sequenceNumber(),
                        done.outputIndex(),
                        done.itemId(),
                        call.callId(),
                        done.name(),
                        OpenAIStateJson.read(done.arguments()));
                if (call.itemId() == null) {
                    CallDescriptor identified = new CallDescriptor(done.itemId(), call.callId(), call.name());
                    calls.put(done.outputIndex(), identified);
                    return List.of(
                            new OpenAITransport.FunctionCallStarted(
                                    done.sequenceNumber(),
                                    done.outputIndex(),
                                    done.itemId(),
                                    identified.callId(),
                                    identified.name()),
                            mapped);
                }
                return List.of(mapped);
            }
            if (event.isImageGenerationCallPartialImage()) {
                var image = event.asImageGenerationCallPartialImage();
                return List.of(new OpenAITransport.ImageDelta(
                        image.sequenceNumber(), imageOutput(image.partialImageB64(), requestedImageOutputFormat)));
            }
            if (event.isCompleted()) {
                terminal = true;
                var completed = event.asCompleted();
                return List.of(new OpenAITransport.ResponseCompleted(
                        completed.sequenceNumber(),
                        OpenAISdkResponseMapper.map(completed.response(), requestId, requestedImageOutputFormat)));
            }
            if (event.isIncomplete()) {
                terminal = true;
                var incomplete = event.asIncomplete();
                return List.of(new OpenAITransport.ResponseCompleted(
                        incomplete.sequenceNumber(),
                        OpenAISdkResponseMapper.map(incomplete.response(), requestId, requestedImageOutputFormat)));
            }
            if (event.isFailed()) {
                terminal = true;
                var failed = event.asFailed();
                Response response = failed.response();
                return List.of(new OpenAITransport.ResponseFailed(
                        failed.sequenceNumber(),
                        response.id(),
                        requestId,
                        response.error()
                                .map(ResponseError::code)
                                .map(ResponseError.Code::asString)
                                .orElse(null)));
            }
            if (event.isError()) {
                terminal = true;
                var error = event.asError();
                return List.of(new OpenAITransport.ResponseFailed(
                        error.sequenceNumber(), null, requestId, error.code().orElse("stream_error")));
            }
            return List.of();
        }

        boolean terminal() {
            return terminal;
        }

        private CallDescriptor requireCall(long outputIndex, String itemId, String name) {
            CallDescriptor call = calls.get(outputIndex);
            if (call == null
                    || call.itemId() != null && !call.itemId().equals(itemId)
                    || name != null && !call.name().equals(name)) {
                throw new OpenAIProtocolException(
                        "OpenAI emitted function arguments without a matching call.", null, "orphan_tool_delta");
            }
            return call;
        }

        private OpenAITransport.ResponseStarted started(long sequenceNumber, Response response) {
            return OpenAISdkResponseMapper.started(sequenceNumber, response, requestId);
        }
    }

    private static OpenAITransport.ImageOutput imageOutput(
            String base64Value, OpenAIImageOutputFormat requestedImageOutputFormat) {
        OpenAIImageOutputFormat format =
                requestedImageOutputFormat == null ? OpenAIImageOutputFormat.PNG : requestedImageOutputFormat;
        return new OpenAITransport.ImageOutput(
                URI.create("data:" + format.mediaType() + ";base64," + base64Value), format.mediaType());
    }

    private static OpenAITransport.ResponseStarted started(long sequence, Response response, String requestId) {
        return new OpenAITransport.ResponseStarted(
                sequence,
                response.id(),
                response.conversation().map(Response.Conversation::id).orElse(null),
                response.model().asString(),
                instant(response.createdAt()),
                requestId,
                mapStatus(response.status().orElse(ResponseStatus.IN_PROGRESS)));
    }

    private record CallDescriptor(String itemId, String callId, String name) {}
}
