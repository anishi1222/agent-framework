// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolInvocationLedgerTest {
    @Test
    void configuredLedger_shouldRecordPendingThenOutcomeAroundOneBodyExecution() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        InMemoryLedger ledger = new InMemoryLedger();
        FunctionTool tool = tool(bodies);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-ledger")))
                .enqueue(emptyResponse());

        // Act
        FunctionLoopResult result;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                FunctionInvocationLoop loop = new FunctionInvocationLoop(
                        source, List.of(tool), executor, InvocationIdFactory.defaultFactory(), ledger)) {
            result = loop.run(new FunctionInvocationRequest("run-ledger", List.of(Message.text(Role.USER, "write"))));
        }

        // Assert
        assertThat(bodies).hasValue(1);
        assertThat(result.toolInvocations()).isEqualTo(1);
        assertThat(ledger.pendingWrites).hasValue(1);
        assertThat(ledger.outcomeWrites).hasValue(1);
        assertThat(ledger.entry.snapshot()).isInstanceOf(InvocationOutcome.class);
    }

    @Test
    void durableTerminalOutcome_shouldBeReusedWithoutExecutingBody() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        FunctionTool tool = tool(bodies);
        FunctionCallContent call = call("call-existing");
        String digest = requestDigest("run-existing", call, tool);
        ToolInvocationResult prior = ToolInvocationResult.succeeded(
                new InvocationId("run-existing:call-existing"), "call-existing", StateValue.string("prior"));
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.entry = new VersionedSnapshot<>(new InvocationOutcome(prior.invocationId(), digest, prior), 2);
        ScriptedToolTurnSource source =
                new ScriptedToolTurnSource().enqueue(response(call)).enqueue(emptyResponse());

        // Act
        FunctionLoopResult result;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                FunctionInvocationLoop loop = new FunctionInvocationLoop(
                        source, List.of(tool), executor, InvocationIdFactory.defaultFactory(), ledger)) {
            result = loop.run(new FunctionInvocationRequest("run-existing", List.of(Message.text(Role.USER, "write"))));
        }

        // Assert
        assertThat(bodies).hasValue(0);
        assertThat(result.toolInvocations()).isZero();
        assertThat(result.history().stream()
                        .flatMap(message -> message.contents().stream())
                        .filter(com.microsoft.agents.core.FunctionResultContent.class::isInstance)
                        .map(com.microsoft.agents.core.FunctionResultContent.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .result())
                .isEqualTo(StateValue.string("prior"));
    }

    @Test
    void rejectedApproval_shouldRecordOwnedTerminalOutcomeWithoutExecutingBody() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        InMemoryLedger ledger = new InMemoryLedger();
        FunctionTool tool = tool(bodies, ToolApprovalMode.ALWAYS_REQUIRE);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(response(call("call-ledger-rejected")));

        // Act
        FunctionLoopResult result;
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                FunctionInvocationLoop loop = new FunctionInvocationLoop(
                        source, List.of(tool), executor, InvocationIdFactory.defaultFactory(), ledger)) {
            FunctionLoopResult suspended = loop.run(
                    new FunctionInvocationRequest("run-ledger-rejected", List.of(Message.text(Role.USER, "write"))));
            result = loop.resume(
                            suspended,
                            List.of(ToolApprovalDecision.reject(
                                    suspended.approvalRequests().getFirst(), "declined")))
                    .result();
        }

        // Assert
        assertThat(bodies).hasValue(0);
        assertThat(result.toolInvocations()).isZero();
        assertThat(ledger.pendingWrites).hasValue(1);
        assertThat(ledger.outcomeWrites).hasValue(1);
        assertThat(ledger.entry.snapshot()).isInstanceOfSatisfying(InvocationOutcome.class, outcome -> assertThat(
                        outcome.result().outcome())
                .isEqualTo(ToolInvocationOutcome.REJECTED));
    }

    @Test
    void durablePendingRecord_shouldFailClosedWithoutClaimingCrashReplayExactlyOnce() {
        // Arrange
        AtomicInteger bodies = new AtomicInteger();
        FunctionTool tool = tool(bodies);
        FunctionCallContent call = call("call-pending");
        String digest = requestDigest("run-pending", call, tool);
        InMemoryLedger ledger = new InMemoryLedger();
        ledger.entry = new VersionedSnapshot<>(
                new InvocationRecord(
                        new InvocationId("run-pending:call-pending"), "run-pending", "call-pending", "write", digest),
                1);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(response(call));

        // Act / Assert
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                FunctionInvocationLoop loop = new FunctionInvocationLoop(
                        source, List.of(tool), executor, InvocationIdFactory.defaultFactory(), ledger)) {
            assertThatThrownBy(() -> loop.runAsync(new FunctionInvocationRequest(
                                    "run-pending", List.of(Message.text(Role.USER, "write"))))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .rootCause()
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessageContaining("atomic checkpoint/ledger storage or provider idempotency");
            assertThat(bodies).hasValue(0);
        }
    }

    @Test
    void callerOwnedExecutor_shouldRemainUsableAfterLoopClose() throws Exception {
        // Arrange
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(emptyResponse());
        FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(), executor);

        // Act
        loop.run(new FunctionInvocationRequest("run-executor", List.of(Message.text(Role.USER, "done"))));
        loop.close();

        // Assert
        assertThat(executor.submit(() -> "still-owned-by-caller").get()).isEqualTo("still-owned-by-caller");
        executor.close();
    }

    private static String requestDigest(String runId, FunctionCallContent call, FunctionTool tool) {
        InvocationId invocationId = new InvocationId(runId + ":" + call.callId());
        return ToolDigests.strings(
                runId,
                call.callId(),
                invocationId.value(),
                tool.name(),
                ToolDigests.state(tool.metadata().inputSchema()),
                ToolDigests.state(call.arguments()));
    }

    private static FunctionTool tool(AtomicInteger bodies) {
        return tool(bodies, ToolApprovalMode.NEVER_REQUIRE);
    }

    private static FunctionTool tool(AtomicInteger bodies, ToolApprovalMode approvalMode) {
        return FunctionTool.create(
                new ToolMetadata(
                        "write",
                        "Writes.",
                        Set.of(ToolCapability.FUNCTION),
                        approvalMode,
                        StateValue.object(Map.of("type", StateValue.string("object"))),
                        StateValue.object(Map.of("type", StateValue.string("string")))),
                (context, arguments) -> {
                    bodies.incrementAndGet();
                    return CompletableFuture.completedFuture(StateValue.string("written"));
                });
    }

    private static FunctionCallContent call(String callId) {
        return new FunctionCallContent(callId, "write", StateValue.object(Map.of("value", StateValue.string("one"))));
    }

    private static ChatResponse response(FunctionCallContent call) {
        return ChatResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                .build();
    }

    private static ChatResponse emptyResponse() {
        return ChatResponse.builder().messages(List.of()).build();
    }

    private static final class InMemoryLedger implements ToolInvocationLedger {
        private VersionedSnapshot<InvocationLedgerEntry> entry;

        private final AtomicInteger pendingWrites = new AtomicInteger();

        private final AtomicInteger outcomeWrites = new AtomicInteger();

        @Override
        public synchronized CompletableFuture<Optional<VersionedSnapshot<InvocationLedgerEntry>>> lookupAsync(
                InvocationId invocationId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(entry));
        }

        @Override
        public synchronized CompletableFuture<VersionedSnapshot<InvocationRecord>> recordPendingAsync(
                InvocationRecord record, long expectedRevision) {
            if (entry != null || expectedRevision != 0) {
                return CompletableFuture.failedFuture(new StorageConflictException("pending conflict"));
            }
            pendingWrites.incrementAndGet();
            VersionedSnapshot<InvocationRecord> pending = new VersionedSnapshot<>(record, 1);
            entry = new VersionedSnapshot<>(record, 1);
            return CompletableFuture.completedFuture(pending);
        }

        @Override
        public synchronized CompletableFuture<VersionedSnapshot<InvocationOutcome>> recordOutcomeAsync(
                InvocationOutcome outcome, long expectedRevision) {
            if (entry == null || expectedRevision != entry.revision()) {
                return CompletableFuture.failedFuture(new StorageConflictException("outcome conflict"));
            }
            outcomeWrites.incrementAndGet();
            VersionedSnapshot<InvocationOutcome> terminal = new VersionedSnapshot<>(outcome, entry.revision() + 1);
            entry = new VersionedSnapshot<>(outcome, terminal.revision());
            return CompletableFuture.completedFuture(terminal);
        }
    }
}
