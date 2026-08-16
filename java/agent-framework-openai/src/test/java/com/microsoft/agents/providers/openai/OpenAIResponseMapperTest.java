// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIResponseMapperTest {
    @Test
    void finiteResponse_shouldMapContentUsageMetadataAndToolFinish() {
        // Arrange
        OpenAITransport.Response response = response(
                OpenAITransport.ResponseStatus.COMPLETED,
                List.of(
                        new OpenAITransport.TextOutput(
                                "message-1", "hello", false, Map.of("tone", StateValue.string("warm"))),
                        new OpenAITransport.ReasoningOutput("reasoning-1", "summary", "encrypted", true),
                        new OpenAITransport.FunctionCallOutput(
                                "call-1",
                                "lookup",
                                StateValue.object(Map.of("city", StateValue.string("Paris"))),
                                "fc-1",
                                "completed"),
                        new OpenAITransport.ImageOutput(URI.create("https://example.test/image.png"), "image/png")),
                new OpenAITransport.Usage(10, 5, 15, 3L, 2L),
                null);

        // Act
        ChatResponse mapped = OpenAIResponseMapper.map(response);

        // Assert
        assertThat(mapped.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(mapped.responseId()).isEqualTo("response-1");
        assertThat(mapped.conversationId()).isEqualTo("conv-1");
        assertThat(mapped.messages()).hasSize(1);
        assertThat(mapped.messages().getFirst().contents())
                .extracting(item -> item.getClass().getSimpleName())
                .containsExactly("TextContent", "ReasoningContent", "FunctionCallContent", "UriContent");
        assertThat(((TextContent) mapped.messages().getFirst().contents().getFirst()).metadata())
                .containsEntry("openai.messageId", StateValue.string("message-1"));
        assertThat(((ReasoningContent) mapped.messages().getFirst().contents().get(1)).protectedData())
                .isEqualTo("encrypted");
        assertThat(((FunctionCallContent)
                                mapped.messages().getFirst().contents().get(2))
                        .metadata())
                .containsEntry("openai.itemId", StateValue.string("fc-1"));
        assertThat(mapped.usage().inputTokens()).contains(BigInteger.TEN);
        assertThat(mapped.usage().values())
                .containsEntry("cacheReadInputTokens", StateValue.integer(3))
                .containsEntry("reasoningOutputTokens", StateValue.integer(2));
        assertThat(mapped.metadata())
                .containsEntry("openai.requestId", StateValue.string("req-1"))
                .containsEntry("openai.status", StateValue.string("completed"));
    }

    @Test
    void incompleteResponse_shouldMapLengthFinishReason() {
        // Arrange
        OpenAITransport.Response response = response(
                OpenAITransport.ResponseStatus.INCOMPLETE,
                List.of(new OpenAITransport.TextOutput("message-1", "partial", false, Map.of())),
                null,
                "max_output_tokens");

        // Act
        ChatResponse mapped = OpenAIResponseMapper.map(response);

        // Assert
        assertThat(mapped.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(mapped.text()).isEqualTo("partial");
    }

    @Test
    void failedAndCancelledResponses_shouldRemainTyped() {
        // Arrange
        OpenAITransport.Response failed = response(OpenAITransport.ResponseStatus.FAILED, List.of(), null, null);
        OpenAITransport.Response cancelled = response(OpenAITransport.ResponseStatus.CANCELLED, List.of(), null, null);

        // Act / Assert
        assertThatThrownBy(() -> OpenAIResponseMapper.map(failed))
                .isInstanceOf(OpenAIProviderException.class)
                .hasMessageContaining("req-1")
                .matches(failure -> "provider_error"
                        .equals(((OpenAIProviderException) failure).errorCode().orElse(null)));
        assertThatThrownBy(() -> OpenAIResponseMapper.map(cancelled)).isInstanceOf(RunCancelledException.class);
    }

    @Test
    void streamMapper_shouldMapTextToolArgumentsAndTerminalUsageExactlyOnce() {
        // Arrange
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        StateValue arguments = StateValue.object(Map.of("city", StateValue.string("Paris")));

        // Act
        updates.addAll(mapper.map(new OpenAITransport.ResponseStarted(
                0,
                "response-1",
                "conv-1",
                "model-1",
                Instant.EPOCH,
                "req-1",
                OpenAITransport.ResponseStatus.IN_PROGRESS)));
        updates.addAll(mapper.map(new OpenAITransport.TextDelta(1, "message-1", "hel", Map.of())));
        updates.addAll(mapper.map(new OpenAITransport.TextDelta(2, "message-1", "lo", Map.of())));
        updates.addAll(mapper.map(new OpenAITransport.FunctionCallStarted(3, 1, "fc-1", "call-1", "lookup")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionArgumentsDelta(4, 1, "fc-1", "{\"city\":\"Paris\"}")));
        updates.addAll(
                mapper.map(new OpenAITransport.FunctionArgumentsDone(5, 1, "fc-1", "call-1", "lookup", arguments)));
        updates.addAll(mapper.map(new OpenAITransport.ResponseCompleted(
                6,
                response(
                        OpenAITransport.ResponseStatus.COMPLETED,
                        List.of(
                                new OpenAITransport.TextOutput("message-1", "hello", false, Map.of()),
                                new OpenAITransport.FunctionCallOutput(
                                        "call-1", "lookup", arguments, "fc-1", "completed")),
                        new OpenAITransport.Usage(4, 2, 6, null, null),
                        null))));
        mapper.requireTerminal();

        // Assert
        assertThat(updates).extracting(ChatResponseUpdate::sequence).containsExactly(0L, 1L, 2L, 5L, 6L);
        assertThat(updates)
                .flatExtracting(ChatResponseUpdate::contents)
                .filteredOn(FunctionCallContent.class::isInstance)
                .hasSize(1);
        assertThat(updates.getLast().finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(updates.getLast().usage().totalTokens()).contains(BigInteger.valueOf(6));
    }

    @Test
    void streamMapper_shouldRejectMismatchedToolArgumentFragments() {
        // Arrange
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        mapper.map(new OpenAITransport.FunctionCallStarted(0, 0, "item-1", "call-1", "lookup"));
        mapper.map(new OpenAITransport.FunctionArgumentsDelta(1, 0, "item-1", "{\"value\":1}"));

        // Act / Assert
        assertThatThrownBy(() -> mapper.map(new OpenAITransport.FunctionArgumentsDone(
                        2, 0, "item-1", "call-1", "lookup", StateValue.object(Map.of("value", StateValue.integer(2))))))
                .isInstanceOf(OpenAIProtocolException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void streamMapper_shouldRejectEventsAfterTerminalAndMissingTerminal() {
        // Arrange
        OpenAIResponseMapper.StreamMapper completed = new OpenAIResponseMapper.StreamMapper();
        completed.map(new OpenAITransport.ResponseCompleted(
                0, response(OpenAITransport.ResponseStatus.COMPLETED, List.of(), null, null)));
        OpenAIResponseMapper.StreamMapper incomplete = new OpenAIResponseMapper.StreamMapper();

        // Act / Assert
        assertThatThrownBy(() -> completed.map(new OpenAITransport.TextDelta(1, "message", "late", Map.of())))
                .isInstanceOf(OpenAIProtocolException.class)
                .hasMessageContaining("after terminal");
        assertThatThrownBy(incomplete::requireTerminal)
                .isInstanceOf(OpenAIProtocolException.class)
                .hasMessageContaining("without a terminal");
    }

    @Test
    void streamMapper_shouldEmitFinalFallbackContentWhenNoDeltaArrived() {
        // Arrange
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();

        // Act
        List<ChatResponseUpdate> updates = mapper.map(new OpenAITransport.ResponseCompleted(
                2,
                response(
                        OpenAITransport.ResponseStatus.COMPLETED,
                        List.of(new OpenAITransport.TextOutput("message", "fallback", false, Map.of())),
                        null,
                        null)));

        // Assert
        assertThat(updates).hasSize(2);
        assertThat(updates.getFirst().text()).isEqualTo("fallback");
        assertThat(updates.getLast().finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void streamMapper_shouldPreserveParallelToolCallModelOrderAcrossInterleavedFragments() {
        // Arrange
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();
        StateValue paris = StateValue.object(Map.of("city", StateValue.string("Paris")));
        StateValue tokyo = StateValue.object(Map.of("city", StateValue.string("Tokyo")));

        // Act
        updates.addAll(mapper.map(new OpenAITransport.ResponseStarted(
                0,
                "response-1",
                "conv-1",
                "model-1",
                Instant.EPOCH,
                "req-1",
                OpenAITransport.ResponseStatus.IN_PROGRESS)));
        updates.addAll(mapper.map(new OpenAITransport.FunctionCallStarted(1, 1, "item-paris", "call-paris", "lookup")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionCallStarted(2, 2, "item-tokyo", "call-tokyo", "lookup")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionArgumentsDelta(3, 1, "item-paris", "{\"city\":\"Par")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionArgumentsDelta(4, 2, "item-tokyo", "{\"city\":\"Tok")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionArgumentsDelta(5, 1, "item-paris", "is\"}")));
        updates.addAll(mapper.map(new OpenAITransport.FunctionArgumentsDelta(6, 2, "item-tokyo", "yo\"}")));
        updates.addAll(mapper.map(
                new OpenAITransport.FunctionArgumentsDone(7, 2, "item-tokyo", "call-tokyo", "lookup", tokyo)));
        updates.addAll(mapper.map(
                new OpenAITransport.FunctionArgumentsDone(8, 1, "item-paris", "call-paris", "lookup", paris)));
        updates.addAll(mapper.map(new OpenAITransport.ResponseCompleted(
                9,
                response(
                        OpenAITransport.ResponseStatus.COMPLETED,
                        List.of(
                                new OpenAITransport.FunctionCallOutput(
                                        "call-paris", "lookup", paris, "item-paris", "completed"),
                                new OpenAITransport.FunctionCallOutput(
                                        "call-tokyo", "lookup", tokyo, "item-tokyo", "completed")),
                        null,
                        null))));
        mapper.requireTerminal();

        // Assert
        List<FunctionCallContent> calls = updates.stream()
                .flatMap(update -> update.contents().stream())
                .filter(FunctionCallContent.class::isInstance)
                .map(FunctionCallContent.class::cast)
                .toList();
        assertThat(calls).extracting(FunctionCallContent::callId).containsExactly("call-paris", "call-tokyo");
        assertThat(calls)
                .extracting(call -> call.metadata().get("openai.itemId"))
                .containsExactly(StateValue.string("item-paris"), StateValue.string("item-tokyo"));
        assertThat(updates).filteredOn(update -> update.finishReason() != null).hasSize(1);
        assertThat(updates)
                .allSatisfy(update ->
                        assertThat(update.metadata()).containsEntry("openai.requestId", StateValue.string("req-1")));
    }

    @Test
    void streamMapper_shouldAggregateTextWithFiniteMessageIdMetadataParity() {
        // Arrange
        OpenAITransport.Response response = response(
                OpenAITransport.ResponseStatus.COMPLETED,
                List.of(new OpenAITransport.TextOutput(
                        "message-1", "hello", false, Map.of("tone", StateValue.string("warm")))),
                null,
                null);
        ChatResponse finite = OpenAIResponseMapper.map(response);
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        ArrayList<ChatResponseUpdate> updates = new ArrayList<>();

        // Act
        updates.addAll(mapper.map(new OpenAITransport.ResponseStarted(
                0,
                "response-1",
                "conv-1",
                "model-1",
                Instant.EPOCH,
                "req-1",
                OpenAITransport.ResponseStatus.IN_PROGRESS)));
        updates.addAll(mapper.map(
                new OpenAITransport.TextDelta(1, "message-1", "hel", Map.of("tone", StateValue.string("warm")))));
        updates.addAll(mapper.map(new OpenAITransport.TextDelta(2, "message-1", "lo", Map.of())));
        updates.addAll(mapper.map(new OpenAITransport.ResponseCompleted(3, response)));
        ChatResponse aggregated = ResponseAggregator.aggregateChat(updates);

        // Assert
        List<ChatResponseUpdate> textUpdates =
                updates.stream().filter(update -> !update.contents().isEmpty()).toList();
        assertThat(textUpdates).allSatisfy(update -> {
            TextContent text = (TextContent) update.contents().getFirst();
            assertThat(update.messageId()).isEqualTo("message-1");
            assertThat(text.metadata()).containsEntry("openai.messageId", StateValue.string(update.messageId()));
        });
        TextContent finiteText =
                (TextContent) finite.messages().getFirst().contents().getFirst();
        TextContent streamedText =
                (TextContent) aggregated.messages().getFirst().contents().getFirst();
        assertThat(streamedText.text()).isEqualTo(finiteText.text());
        assertThat(streamedText.metadata()).isEqualTo(finiteText.metadata());
        assertThat(aggregated.metadata()).isEqualTo(finite.metadata());
    }

    @Test
    void finiteResponse_shouldPreserveUnknownGeneratedImageMediaTypeWithoutClaimingPng() {
        // Arrange
        OpenAITransport.Response response = response(
                OpenAITransport.ResponseStatus.COMPLETED,
                List.of(new OpenAITransport.ImageOutput(
                        URI.create("data:application/octet-stream;base64,AQID"), "application/octet-stream")),
                null,
                null);

        // Act
        ChatResponse mapped = OpenAIResponseMapper.map(response);

        // Assert
        DataContent image =
                (DataContent) mapped.messages().getFirst().contents().getFirst();
        assertThat(image.mediaType()).isEqualTo("application/octet-stream");
        assertThat(image.data()).containsExactly(1, 2, 3);
    }

    private static OpenAITransport.Response response(
            OpenAITransport.ResponseStatus status,
            List<OpenAITransport.OutputItem> outputs,
            OpenAITransport.Usage usage,
            String incompleteReason) {
        return new OpenAITransport.Response(
                "response-1",
                "conv-1",
                "model-1",
                Instant.EPOCH,
                status,
                outputs,
                usage,
                Map.of("region", StateValue.string("test")),
                "req-1",
                incompleteReason,
                "provider_error");
    }
}
