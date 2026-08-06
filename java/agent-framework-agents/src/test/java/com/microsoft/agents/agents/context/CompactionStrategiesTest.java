// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompactionStrategiesTest {
    @Test
    void slidingWindowPreservesInstructionsAndRecentTurnsInOrder() {
        // Arrange
        List<Message> history = List.of(
                text(Role.SYSTEM, "system", "s"),
                text(Role.of("developer"), "developer", "d"),
                text(Role.USER, "u1", "u1"),
                text(Role.ASSISTANT, "a1", "a1"),
                text(Role.USER, "u2", "u2"),
                text(Role.ASSISTANT, "a2", "a2"),
                text(Role.USER, "u3", "u3"),
                text(Role.ASSISTANT, "a3", "a3"));

        // Act
        CompactionResult result = Compactions.compactAsync(new SlidingWindowCompactionStrategy(2), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).extracting(Message::messageId).containsExactly("s", "d", "u2", "a2", "u3", "a3");
        assertThat(result.audit().removedMessageIds()).containsExactly("u1", "a1");
        assertThat(result.audit().limitStatus()).isEqualTo(CompactionLimitStatus.WITHIN_LIMIT);
        assertThat(history).hasSize(8);
    }

    @Test
    void truncationUsesWholeGroupsAndDeterministicAudit() {
        // Arrange
        List<Message> history = List.of(
                text(Role.SYSTEM, "system", "s"),
                text(Role.USER, "u1", "u1"),
                text(Role.ASSISTANT, "a1", "a1"),
                text(Role.USER, "u2", "u2"),
                text(Role.ASSISTANT, "a2", "a2"),
                text(Role.USER, "u3", "u3"),
                text(Role.ASSISTANT, "a3", "a3"));
        TruncationCompactionStrategy strategy = new TruncationCompactionStrategy(6, 4, 1);

        // Act
        CompactionResult first = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();
        CompactionResult second = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.messages()).extracting(Message::messageId).containsExactly("s", "a2", "u3", "a3");
        assertThat(first.audit().changed()).isTrue();
        assertThat(first.audit().strategy()).isEqualTo("TruncationCompactionStrategy");
    }

    @Test
    void tokenBudgetNeverSplitsFunctionCallAndResult() {
        // Arrange
        Message call = Message.builder(Role.ASSISTANT)
                .contents(List.of(new FunctionCallContent(
                        "call-1", "weather", StateValue.object(java.util.Map.of("city", StateValue.string("Paris"))))))
                .messageId("call-message")
                .build();
        Message unrelated = text(Role.ASSISTANT, "intermediate", "middle");
        Message result = Message.builder(Role.TOOL)
                .contents(List.of(new FunctionResultContent("call-1", StateValue.string("sunny"))))
                .messageId("result-message")
                .build();
        List<Message> history = List.of(
                text(Role.USER, "old", "u1"),
                call,
                unrelated,
                result,
                text(Role.USER, "new", "u2"),
                text(Role.ASSISTANT, "answer", "a2"));
        TokenEstimator estimator = message -> 10;

        // Act
        CompactionResult resultValue = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(25, 1), history, estimator, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(resultValue.messages()).extracting(Message::messageId).containsExactly("u2", "a2");
        assertThat(resultValue.audit().removedMessageIds()).contains("call-message", "result-message");
        assertThat(resultValue.audit().removedMessageIds().contains("call-message"))
                .isEqualTo(resultValue.audit().removedMessageIds().contains("result-message"));
    }

    @Test
    void tokenBudgetReportsHugeRequiredMessageOverflow() {
        // Arrange
        Message huge = text(Role.USER, "x".repeat(10_000), "huge");
        TokenBudgetCompactionStrategy strategy = new TokenBudgetCompactionStrategy(10, 1);

        // Act
        CompactionResult result = Compactions.compactAsync(strategy, List.of(huge))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).containsExactly(huge);
        assertThat(result.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(result.audit().compactedEstimatedTokens()).isGreaterThan(10);
    }

    @Test
    void providerTokenEstimatorOverrideControlsBudget() {
        // Arrange
        List<Message> history = List.of(
                text(Role.USER, "small", "u1"), text(Role.ASSISTANT, "small", "a1"), text(Role.USER, "small", "u2"));
        TokenEstimator providerEstimator = message -> "u1".equals(message.messageId()) ? 100 : 1;

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(5, 1),
                        history,
                        providerEstimator,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).extracting(Message::messageId).containsExactly("a1", "u2");
        assertThat(result.audit().originalEstimatedTokens()).isEqualTo(102);
    }

    @Test
    void unresolvedCallAndApprovalMetadataRemainProtected() {
        // Arrange
        Message pendingCall = Message.builder(Role.ASSISTANT)
                .contents(
                        List.of(new FunctionCallContent("pending", "dangerous", StateValue.object(java.util.Map.of()))))
                .messageId("pending-call")
                .metadata(java.util.Map.of("approval.state", StateValue.string("pending")))
                .build();
        List<Message> history = List.of(text(Role.USER, "old", "u1"), pendingCall, text(Role.USER, "new", "u2"));

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(1, 0), history, message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).extracting(Message::messageId).contains("pending-call", "u2");
        assertThat(result.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
    }

    @Test
    void summarizationReplacesOnlyAfterSuccessfulResponse() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("facts and decisions");
        List<Message> history = conversation();
        SummarizationCompactionStrategy strategy = new SummarizationCompactionStrategy(client, 4, 1, 1_000);

        // Act
        CompactionResult result = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages().getFirst().role()).isEqualTo(Role.SYSTEM);
        assertThat(result.messages()).anyMatch(message -> message.text().startsWith("[Summary]"));
        assertThat(result.messages()).extracting(Message::messageId).contains("u3", "a3");
        assertThat(result.audit().summaryMessageId()).startsWith("summary-");
        assertThat(result.audit().summarizedMessageIds()).containsExactly("u1", "a1", "u2", "a2");
        assertThat(history).doesNotContain(result.messages().get(1));
        assertThat(client.lastRequest.options().metadata())
                .containsEntry(
                        SummarizationCompactionStrategy.SUPPRESS_INSTRUMENTATION_METADATA_KEY, StateValue.bool(true));
    }

    @Test
    void summarizationFailurePropagatesAndSourceRemainsUnchanged() {
        // Arrange
        RuntimeException failure = new RuntimeException("summary failed");
        RecordingChatClient client = RecordingChatClient.fail(failure);
        List<Message> history = conversation();
        List<Message> original = List.copyOf(history);
        SummarizationCompactionStrategy strategy = new SummarizationCompactionStrategy(client, 4, 1, 1_000);

        // Act / Assert
        assertThatThrownBy(() -> Compactions.compactAsync(strategy, history)
                        .toCompletableFuture()
                        .join())
                .hasRootCause(failure);
        assertThat(history).containsExactlyElementsOf(original);
    }

    @Test
    void summarizationCancellationPropagatesAndDoesNotReplaceSource() {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        RecordingChatClient client = new RecordingChatClient(request -> pending);
        List<Message> history = conversation();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletionStage<CompactionResult> stage = Compactions.compactAsync(
                new SummarizationCompactionStrategy(client, 4, 1, 1_000),
                history,
                TokenEstimator.heuristic(),
                cancellation);

        // Act
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture()
                        .orTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                        .join())
                .hasRootCauseInstanceOf(RunCancelledException.class);
        assertThat(history).containsExactlyElementsOf(conversation());
        pending.complete(response("too late"));
    }

    @Test
    void summarizationBudgetKeepsHugeAtomicGroupAndReportsRequiredOverflow() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("unused");
        List<Message> history = List.of(
                text(Role.USER, "x".repeat(10_000), "huge"),
                text(Role.ASSISTANT, "old answer", "old"),
                text(Role.USER, "recent", "recent"));

        // Act
        SummarizationCompactionStrategy strategy = new SummarizationCompactionStrategy(client, 2, 1, 10);
        CompactionResult first = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();
        CompactionResult second = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.messages()).containsExactlyElementsOf(history);
        assertThat(first.audit().changed()).isFalse();
        assertThat(first.audit().configuredLimit()).isEqualTo(10);
        assertThat(first.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void summarizationBudgetNeverSplitsHugeDuplicateCallIdPairs() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("unused");
        Message firstCall = call("shared", "first-call", "x".repeat(2_000));
        Message secondCall = call("shared", "second-call", "y".repeat(2_000));
        Message firstResult = result("shared", "first-result", "a".repeat(2_000));
        Message secondResult = result("shared", "second-result", "b".repeat(2_000));
        Message recent = text(Role.USER, "recent", "recent");
        List<Message> history = List.of(firstCall, firstResult, secondCall, secondResult, recent);

        // Act
        CompactionResult compacted = Compactions.compactAsync(
                        new SummarizationCompactionStrategy(client, 2, 1, 10),
                        history,
                        message -> message == recent ? 1 : 100,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(compacted.messages()).containsExactlyElementsOf(history);
        assertThat(compacted.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(client.calls).hasValue(0);
        List<CompactionMessageGroup> groups = MessageGroupAnnotator.groupMessages(history, message -> 1);
        assertThat(groups)
                .filteredOn(group -> group.kind() == CompactionGroupKind.TOOL)
                .extracting(CompactionMessageGroup::messageIndexes)
                .containsExactly(List.of(0, 1), List.of(2, 3));
    }

    @Test
    void duplicateMessageAndCallIdsUseUniqueGroupsWithoutOverProtectingOldPairs() {
        // Arrange
        Message old = text(Role.USER, "old", "duplicate");
        Message firstCall = call("shared", "duplicate", "first");
        Message firstResult = result("shared", "duplicate", "first-result");
        Message secondCall = call("shared", "duplicate", "second");
        Message secondResult = result("shared", "duplicate", "second-result");
        Message recent = text(Role.USER, "recent", "duplicate");
        Message answer = text(Role.ASSISTANT, "answer", "duplicate");
        List<Message> history = List.of(old, firstCall, firstResult, secondCall, secondResult, recent, answer);

        // Act
        List<CompactionMessageGroup> groups = MessageGroupAnnotator.groupMessages(history, message -> 1);
        CompactionResult compacted = Compactions.compactAsync(new SlidingWindowCompactionStrategy(1), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(groups).extracting(CompactionMessageGroup::id).doesNotHaveDuplicates();
        assertThat(groups)
                .filteredOn(group -> group.kind() == CompactionGroupKind.TOOL)
                .extracting(CompactionMessageGroup::messageIndexes)
                .containsExactly(List.of(1, 2), List.of(3, 4));
        assertThat(compacted.messages()).containsExactly(recent, answer);
        assertThat(compacted.audit().changed()).isTrue();
    }

    @Test
    void concurrentlyAmbiguousDuplicateCallsRemainProtectedWithoutProtectingLatestDuplicateId() {
        // Arrange
        Message old = text(Role.USER, "old", "duplicate");
        Message firstCall = call("ambiguous", "duplicate", "first");
        Message secondCall = call("ambiguous", "duplicate", "second");
        Message result = result("ambiguous", "duplicate", "result");
        Message recent = text(Role.USER, "recent", "duplicate");
        List<Message> history = List.of(old, firstCall, secondCall, result, recent);

        // Act
        List<CompactionMessageGroup> groups = MessageGroupAnnotator.groupMessages(history, message -> 1);
        CompactionResult compacted = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(1, 0), history, message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(groups).extracting(CompactionMessageGroup::id).doesNotHaveDuplicates();
        assertThat(groups)
                .filteredOn(group -> group.messageIndexes().getFirst() >= 1
                        && group.messageIndexes().getFirst() <= 3)
                .allMatch(CompactionMessageGroup::structurallyProtected);
        assertThat(compacted.messages()).containsExactlyElementsOf(history);
        assertThat(compacted.audit().changed()).isFalse();
        assertThat(compacted.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
    }

    @Test
    void informationalOnlyCallDoesNotBecomePermanentlyProtected() {
        // Arrange
        Message informational = Message.builder(Role.ASSISTANT)
                .contents(List.of(
                        new FunctionCallContent("info", "notice", StateValue.nullValue(), true, java.util.Map.of())))
                .messageId("informational")
                .build();
        List<Message> history =
                List.of(text(Role.USER, "old", "old"), informational, text(Role.USER, "recent", "recent"));

        // Act
        CompactionResult compacted = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(1, 0), history, message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(compacted.messages()).containsExactly(history.getLast());
        assertThat(compacted.audit().limitStatus()).isEqualTo(CompactionLimitStatus.WITHIN_LIMIT);
    }

    @Test
    void repeatedSummarizationReplacesPriorGeneratedSummaryInsteadOfAccumulating() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("facts and decisions");
        SummarizationCompactionStrategy strategy = new SummarizationCompactionStrategy(client, 4, 1, 1_000);

        // Act
        CompactionResult first = Compactions.compactAsync(strategy, conversation())
                .toCompletableFuture()
                .join();
        java.util.ArrayList<Message> nextHistory = new java.util.ArrayList<>(first.messages());
        nextHistory.add(text(Role.USER, "fourth", "u4"));
        nextHistory.add(text(Role.ASSISTANT, "fourth answer", "a4"));
        CompactionResult second = Compactions.compactAsync(strategy, nextHistory)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(second.messages())
                .filteredOn(message -> message.text().startsWith("[Summary]"))
                .hasSize(1);
        assertThat(second.messages()).extracting(Message::messageId).contains("u4", "a4");
        assertThat(client.calls).hasValue(2);
    }

    @Test
    void generatedSummaryAloneIsNotResummarizedWithoutProgress() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("unused");
        Message priorSummary = Message.builder(Role.ASSISTANT)
                .contents(List.of(new com.microsoft.agents.core.TextContent("[Summary]\nprior")))
                .messageId("summary-prior")
                .metadata(java.util.Map.of(
                        SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY,
                        StateValue.array(List.of(StateValue.string("old"))),
                        SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY,
                        StateValue.array(List.of(StateValue.string("group:old")))))
                .build();
        Message protectedCall = Message.builder(Role.ASSISTANT)
                .contents(List.of(new FunctionCallContent("pending", "approval", StateValue.nullValue())))
                .messageId("pending")
                .metadata(java.util.Map.of("approval.state", StateValue.string("pending")))
                .build();
        List<Message> history = List.of(priorSummary, protectedCall, text(Role.USER, "recent", "recent"));

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new SummarizationCompactionStrategy(client, 2, 1, 1_000), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).containsExactlyElementsOf(history);
        assertThat(result.audit().changed()).isFalse();
        assertThat(result.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void noProgressSummaryBeforeInstructionDoesNotBlockLaterEligibleRange() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("later summary");
        Message priorSummary = Message.builder(Role.ASSISTANT)
                .contents(List.of(new com.microsoft.agents.core.TextContent("[Summary]\nprior")))
                .messageId("summary-prior")
                .metadata(java.util.Map.of(
                        SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY,
                        StateValue.array(List.of(StateValue.string("old"))),
                        SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY,
                        StateValue.array(List.of(StateValue.string("group:old")))))
                .build();
        Message instruction = text(Role.of("developer"), "instruction", "instruction");
        Message later = text(Role.USER, "later", "later");
        Message laterAnswer = text(Role.ASSISTANT, "later answer", "later-answer");
        Message recent = text(Role.USER, "recent", "recent");
        List<Message> history = List.of(priorSummary, instruction, later, laterAnswer, recent);

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new SummarizationCompactionStrategy(client, 3, 1, 10_000), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(client.calls).hasValue(1);
        assertThat(client.lastRequest.messages().get(1).text())
                .contains("later", "later answer")
                .doesNotContain("prior", "instruction");
        assertThat(result.messages()).contains(priorSummary, instruction, recent);
        assertThat(result.audit().changed()).isTrue();
    }

    @Test
    void instructionContainingToolCallRemainsProtectedWithItsResult() {
        // Arrange
        Message instructionCall = Message.builder(Role.SYSTEM)
                .contents(List.of(new FunctionCallContent("instruction-call", "bootstrap", StateValue.nullValue())))
                .messageId("instruction")
                .build();
        Message toolResult = result("instruction-call", "instruction-result", "configured");
        Message recent = text(Role.USER, "recent", "recent");
        List<Message> history = List.of(instructionCall, toolResult, recent);

        // Act
        List<CompactionMessageGroup> groups = MessageGroupAnnotator.groupMessages(history, message -> 1);
        CompactionResult compacted = Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(1, 0), history, message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(groups.getFirst().kind()).isEqualTo(CompactionGroupKind.INSTRUCTION);
        assertThat(groups.getFirst().messageIndexes()).containsExactly(0, 1);
        assertThat(compacted.messages()).containsExactlyElementsOf(history);
        assertThat(compacted.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
    }

    @Test
    void summaryBudgetMeasuresExactPromptAndFormattedTranscript() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("unused");
        List<Message> history =
                List.of(text(Role.USER, "a", "u1"), text(Role.ASSISTANT, "b", "a1"), text(Role.USER, "c", "u2"));
        TokenEstimator textLength = message -> message.text().length();

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new SummarizationCompactionStrategy(client, 2, 1, 10, "p"),
                        history,
                        textLength,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).containsExactlyElementsOf(history);
        assertThat(result.audit().limitStatus()).isEqualTo(CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT);
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void mixedTextAndToolContentIsRetainedInSummarizerTranscript() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("tool outcome");
        Message mixed = Message.builder(Role.ASSISTANT)
                .contents(List.of(
                        new com.microsoft.agents.core.TextContent("checking"),
                        new FunctionCallContent(
                                "call",
                                "lookup",
                                StateValue.object(java.util.Map.of("city", StateValue.string("Paris"))))))
                .messageId("mixed")
                .build();
        Message toolResult = result("call", "tool-result", "sunny");
        List<Message> history =
                List.of(text(Role.USER, "old", "old"), mixed, toolResult, text(Role.USER, "recent", "recent"));

        // Act
        Compactions.compactAsync(new SummarizationCompactionStrategy(client, 2, 1, 10_000), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(client.lastRequest.messages().get(1).text())
                .contains("checking", "lookup", "city", "Paris", "sunny");
    }

    @Test
    void summarizationStopsBeforeProtectedInstructionWithoutReorderingLaterHistory() {
        // Arrange
        RecordingChatClient client = RecordingChatClient.succeed("old summary");
        Message old = text(Role.USER, "old", "old");
        Message instruction = text(Role.of("developer"), "new instruction", "instruction");
        Message later = text(Role.USER, "later", "later");
        Message laterAnswer = text(Role.ASSISTANT, "later answer", "later-answer");
        Message recent = text(Role.USER, "recent", "recent");
        List<Message> history = List.of(old, instruction, later, laterAnswer, recent);

        // Act
        CompactionResult result = Compactions.compactAsync(
                        new SummarizationCompactionStrategy(client, 3, 1, 10_000), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(client.lastRequest.messages().get(1).text())
                .contains("old")
                .doesNotContain("later", "new instruction");
        assertThat(result.messages().get(1)).isSameAs(instruction);
        assertThat(result.messages()).containsSubsequence(instruction, later, laterAnswer, recent);
    }

    @Test
    void emptyHistoryProducesDeterministicNoopAudit() {
        // Arrange
        SlidingWindowCompactionStrategy strategy = new SlidingWindowCompactionStrategy(1);

        // Act
        CompactionResult result = Compactions.compactAsync(strategy, List.of())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result.messages()).isEmpty();
        assertThat(result.audit().changed()).isFalse();
        assertThat(result.audit().toStateValue().values()).containsKey("limitStatus");
    }

    @Test
    void compactionSerializationKeepsHugeExponentNumbersCanonicalAndBounded() {
        // Arrange
        Message message = Message.builder(Role.USER)
                .contents(List.of(new com.microsoft.agents.core.MetadataContent(
                        java.util.Map.of("number", StateValue.number(new java.math.BigDecimal("1e100000000"))))))
                .build();

        // Act
        String serialized = CompactionText.message(message);

        // Assert
        assertThat(serialized).contains("1E+100000000").hasSizeLessThan(256);
    }

    private static List<Message> conversation() {
        return List.of(
                text(Role.SYSTEM, "system", "s"),
                text(Role.USER, "first", "u1"),
                text(Role.ASSISTANT, "first answer", "a1"),
                text(Role.USER, "second", "u2"),
                text(Role.ASSISTANT, "second answer", "a2"),
                text(Role.USER, "third", "u3"),
                text(Role.ASSISTANT, "third answer", "a3"));
    }

    private static Message text(Role role, String value, String id) {
        return Message.builder(role)
                .contents(List.of(new com.microsoft.agents.core.TextContent(value)))
                .messageId(id)
                .build();
    }

    private static Message call(String callId, String messageId, String argument) {
        return Message.builder(Role.ASSISTANT)
                .contents(List.of(new FunctionCallContent(
                        callId, "tool", StateValue.object(java.util.Map.of("value", StateValue.string(argument))))))
                .messageId(messageId)
                .build();
    }

    private static Message result(String callId, String messageId, String value) {
        return Message.builder(Role.TOOL)
                .contents(List.of(new FunctionResultContent(callId, StateValue.string(value))))
                .messageId(messageId)
                .build();
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .build();
    }

    private static final class RecordingChatClient implements ChatClient {
        private final java.util.function.Function<ChatClientRequest, CompletionStage<ChatResponse>> handler;

        private final AtomicInteger calls = new AtomicInteger();

        private ChatClientRequest lastRequest;

        private RecordingChatClient(
                java.util.function.Function<ChatClientRequest, CompletionStage<ChatResponse>> handler) {
            this.handler = handler;
        }

        static RecordingChatClient succeed(String text) {
            return new RecordingChatClient(request -> CompletableFuture.completedFuture(response(text)));
        }

        static RecordingChatClient fail(Throwable failure) {
            return new RecordingChatClient(request -> CompletableFuture.failedFuture(failure));
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            calls.incrementAndGet();
            lastRequest = request;
            return handler.apply(request);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            throw new UnsupportedOperationException();
        }
    }
}
