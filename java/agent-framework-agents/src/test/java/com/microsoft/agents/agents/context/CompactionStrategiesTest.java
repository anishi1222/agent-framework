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
    void selectiveToolCallCompactionRemovesOnlyOlderCompleteToolGroups() {
        // Arrange
        Message firstCall = call("first", "first-call", "one");
        Message firstResult = result("first", "first-result", "r1");
        Message secondCall = call("second", "second-call", "two");
        Message secondResult = result("second", "second-result", "r2");
        List<Message> history = List.of(
                text(Role.USER, "question", "user"),
                firstCall,
                firstResult,
                secondCall,
                secondResult,
                text(Role.ASSISTANT, "done", "done"));

        // Act
        CompactionResult compacted = Compactions.compactAsync(new SelectiveToolCallCompactionStrategy(1), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(compacted.messages())
                .containsExactly(history.getFirst(), secondCall, secondResult, history.getLast());
        assertThat(compacted.audit().removedMessageIds()).containsExactly("first-call", "first-result");
        assertThatThrownBy(() -> new SelectiveToolCallCompactionStrategy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectiveToolCallCompactionPreservesUnresolvedToolGroupsAtZeroKeepCount() {
        // Arrange
        Message completeCall = call("complete", "complete-call", "one");
        Message completeResult = result("complete", "complete-result", "ok");
        Message pending = call("pending", "pending-call", "two");
        List<Message> history = List.of(text(Role.USER, "question", "user"), completeCall, completeResult, pending);

        // Act
        CompactionResult compacted = Compactions.compactAsync(new SelectiveToolCallCompactionStrategy(0), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(compacted.messages()).containsExactly(history.getFirst(), pending);
        assertThat(compacted.audit().removedMessageIds()).containsExactly("complete-call", "complete-result");
    }

    @Test
    void selectiveToolCallCompactionPreservesResolvedPreambleToolGroup() {
        // Arrange
        Message preambleCall = call("preamble", "preamble-call", "one");
        Message preambleResult = result("preamble", "preamble-result", "ok");
        List<Message> history = List.of(
                preambleCall,
                preambleResult,
                text(Role.USER, "question", "user"),
                text(Role.ASSISTANT, "answer", "answer"));

        // Act
        CompactionResult compacted = Compactions.compactAsync(new SelectiveToolCallCompactionStrategy(0), history)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(compacted.messages()).containsExactlyElementsOf(history);
        assertThat(compacted.audit().changed()).isFalse();
    }

    @Test
    void toolResultCompactionSummarizesOlderGroupsWithBoundedDeterministicMetadata() {
        // Arrange
        Message firstCall = call("first", "first-call", "one");
        Message firstResult = result("first", "first-result", "r1");
        Message secondCall = call("second", "second-call", "two");
        Message secondResult = result("second", "second-result", "r2");
        List<Message> history = List.of(
                text(Role.USER, "question", "user"),
                firstCall,
                firstResult,
                secondCall,
                secondResult,
                text(Role.ASSISTANT, "done", "done"));
        ToolResultCompactionStrategy strategy = new ToolResultCompactionStrategy(1);

        // Act
        CompactionResult first = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();
        CompactionResult second = Compactions.compactAsync(strategy, history)
                .toCompletableFuture()
                .join();
        CompactionResult repeated = Compactions.compactAsync(strategy, first.messages())
                .toCompletableFuture()
                .join();

        // Assert
        Message summary = first.messages().get(1);
        assertThat(first).isEqualTo(second);
        assertThat(summary.text()).isEqualTo("[Tool results: tool: r1]");
        assertThat(summary.metadata())
                .containsKeys(
                        SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY,
                        SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY);
        assertThat(first.messages()).containsSubsequence(summary, secondCall, secondResult);
        assertThat(first.audit().summarizedMessageIds()).containsExactly("first-call", "first-result");
        assertThat(first.audit().summaryMessageIds()).containsExactly(summary.messageId());
        assertThat(repeated.messages()).containsExactlyElementsOf(first.messages());
        assertThat(repeated.audit().changed()).isFalse();
        assertThatThrownBy(() -> new ToolResultCompactionStrategy(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolResultCompactionBoundsLargeResultWithoutRestoringTail() {
        // Arrange
        String large = "file-start\n" + "line contents\n".repeat(500) + "file-end";
        List<Message> history = List.of(
                text(Role.USER, "question", "user"),
                call("read", "read-call", "file"),
                result("read", "read-result", large),
                text(Role.ASSISTANT, "done", "done"));

        // Act
        CompactionResult compacted = Compactions.compactAsync(new ToolResultCompactionStrategy(0), history)
                .toCompletableFuture()
                .join();

        // Assert
        String summary = compacted.messages().get(1).text();
        assertThat(summary)
                .startsWith("[Tool results: tool: file-start")
                .contains("[truncated]")
                .doesNotContain("file-end")
                .hasSizeLessThanOrEqualTo(ToolResultCompactionStrategy.SUMMARY_MAX_CHARS);
    }

    @Test
    void toolResultCompactionReportsEveryGeneratedSummaryId() {
        // Arrange
        List<Message> history = List.of(
                text(Role.USER, "question", "user"),
                call("first", "first-call", "one"),
                result("first", "first-result", "r1"),
                call("second", "second-call", "two"),
                result("second", "second-result", "r2"),
                call("third", "third-call", "three"),
                result("third", "third-result", "r3"),
                text(Role.ASSISTANT, "done", "done"));

        // Act
        CompactionResult compacted = Compactions.compactAsync(new ToolResultCompactionStrategy(1), history)
                .toCompletableFuture()
                .join();

        // Assert
        List<String> generatedIds = compacted.messages().stream()
                .filter(message -> message.text().startsWith("[Tool results:"))
                .map(Message::messageId)
                .toList();
        assertThat(generatedIds).hasSize(2);
        assertThat(compacted.audit().summaryMessageIds()).containsExactlyElementsOf(generatedIds);
        assertThat(compacted.audit().summaryMessageId()).isNull();
        assertThat(compacted.audit().toStateValue().values()).containsKey("summaryMessageIds");
    }

    @Test
    void tokenBudgetCompositionStopsAfterBudgetAndFallsBackAtomically() {
        // Arrange
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        CompactionStrategy first = request -> {
            firstCalls.incrementAndGet();
            return CompletableFuture.completedFuture(CompactionSupport.projectedResult(
                    "first",
                    request,
                    List.of(request.messages().getLast()),
                    null,
                    CompactionLimitStatus.NOT_APPLICABLE));
        };
        CompactionStrategy second = request -> {
            secondCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged("second", request, CompactionSupport.groups(request), null));
        };
        List<Message> simple = List.of(text(Role.USER, "old", "old"), text(Role.ASSISTANT, "new", "new"));
        TokenBudgetComposedStrategy earlyStop = new TokenBudgetComposedStrategy(1, List.of(first, second));
        List<Message> atomic = List.of(
                text(Role.USER, "old", "old"),
                call("call", "call-message", "value"),
                result("call", "result-message", "result"),
                text(Role.USER, "latest", "latest"));

        // Act
        CompactionResult stopped = Compactions.compactAsync(
                        earlyStop, simple, message -> 1, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        CompactionResult fallback = Compactions.compactAsync(
                        new TokenBudgetComposedStrategy(1, List.of()),
                        atomic,
                        message -> 1,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(stopped.messages()).extracting(Message::messageId).containsExactly("new");
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(0);
        assertThat(fallback.messages()).extracting(Message::messageId).containsExactly("latest");
        assertThat(fallback.audit().removedMessageIds()).contains("call-message", "result-message");
    }

    @Test
    void contextWindowCompactionAppliesThresholdsAndValidatesConfiguration() {
        // Arrange
        List<Message> underThreshold = List.of(
                text(Role.SYSTEM, "system", "system"),
                text(Role.USER, "hello", "user"),
                text(Role.ASSISTANT, "hi", "assistant"));
        List<Message> toolHeavy = List.of(
                text(Role.USER, "question", "user"),
                call("first", "first-call", "one"),
                result("first", "first-result", "r1"),
                call("second", "second-call", "two"),
                result("second", "second-result", "r2"),
                text(Role.ASSISTANT, "done", "done"));
        TokenEstimator toolEstimator = message -> {
            if (message.text().startsWith("[Tool results:")) {
                return 1;
            }
            if ("first-result".equals(message.messageId()) || "second-result".equals(message.messageId())) {
                return 30;
            }
            return 1;
        };
        List<Message> textHeavy = List.of(
                text(Role.SYSTEM, "system", "system"),
                text(Role.USER, "old user", "u1"),
                text(Role.ASSISTANT, "old answer", "a1"),
                text(Role.USER, "new user", "u2"),
                text(Role.ASSISTANT, "new answer", "a2"));

        // Act
        CompactionResult unchanged = Compactions.compactAsync(
                        new ContextWindowCompactionStrategy(1_000, 200),
                        underThreshold,
                        message -> 1,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        CompactionResult summarized = Compactions.compactAsync(
                        new ContextWindowCompactionStrategy(100, 0, 0.5, 0.8, 1),
                        toolHeavy,
                        toolEstimator,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        CompactionResult truncated = Compactions.compactAsync(
                        new ContextWindowCompactionStrategy(100, 0),
                        textHeavy,
                        message -> 20,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(unchanged.messages()).containsExactlyElementsOf(underThreshold);
        assertThat(summarized.messages())
                .anyMatch(message -> message.text().startsWith("[Tool results:"))
                .contains(toolHeavy.get(3), toolHeavy.get(4));
        assertThat(truncated.messages())
                .extracting(Message::messageId)
                .contains("system", "a2")
                .doesNotContain("u1", "a1");
        assertThatThrownBy(() -> new ContextWindowCompactionStrategy(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextWindowCompactionStrategy(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextWindowCompactionStrategy(100, 0, 0, 0.8, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextWindowCompactionStrategy(100, 0, 0.8, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
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
