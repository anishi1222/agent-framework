// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.shell.ShellPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodeActExecutorTest {
    @TempDir
    Path workspace;

    @Test
    void run_shouldExecuteOnlyAfterExactApproval() {
        // Arrange
        CopyOnWriteArrayList<String> approvalIds = new CopyOnWriteArrayList<>();
        CodeActOptions options = optionsBuilder(allow("^echo\\b"))
                .approvalHandler((request, cancellation) -> {
                    approvalIds.add(request.approvalId().value());
                    StateValue.ArrayValue steps =
                            (StateValue.ArrayValue) request.arguments().require("steps");
                    assertThat(steps.values()).hasSize(1);
                    return CompletableFuture.completedFuture(ToolApprovalDecision.approve(request));
                })
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(CodeActProgram.ofCommands("echo approved"));

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.COMPLETED),
                    () -> assertThat(result.steps()).hasSize(1),
                    () -> assertThat(result.steps().getFirst().stdout()).contains("approved"),
                    () -> assertThat(result.events())
                            .extracting(CodeActEvent::type)
                            .containsSubsequence(
                                    CodeActEventType.APPROVAL_REQUESTED,
                                    CodeActEventType.APPROVAL_GRANTED,
                                    CodeActEventType.STEP_STARTED,
                                    CodeActEventType.STEP_COMPLETED,
                                    CodeActEventType.RUN_COMPLETED),
                    () -> assertThat(approvalIds)
                            .containsExactly("approval-"
                                    + result.events().stream()
                                            .filter(event -> event.type() == CodeActEventType.APPROVAL_REQUESTED)
                                            .map(event -> ((StateValue.StringValue)
                                                            event.data().require("requestDigest"))
                                                    .value())
                                            .findFirst()
                                            .orElseThrow()));
        }
    }

    @Test
    void run_shouldNotStartShellWhenApprovalIsDenied() {
        // Arrange
        Path deniedOutput = workspace.resolve("denied.txt");
        CodeActOptions options = optionsBuilder(allow("^echo\\b"))
                .approvalHandler((request, cancellation) -> CompletableFuture.completedFuture(
                        ToolApprovalDecision.reject(request, "operator denied local execution")))
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(CodeActProgram.ofCommands("echo should-not-run > denied.txt"));

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.APPROVAL_DENIED),
                    () -> assertThat(result.steps()).isEmpty(),
                    () -> assertThat(result.detail()).contains("operator denied"),
                    () -> assertThat(deniedOutput).doesNotExist(),
                    () -> assertThat(result.events())
                            .extracting(CodeActEvent::type)
                            .doesNotContain(CodeActEventType.STEP_STARTED));
        }
    }

    @Test
    void run_shouldTerminateTimedOutStepThroughShellRuntime() {
        // Arrange
        CodeActOptions options = optionsBuilder(allow("^(sleep|Start-Sleep)\\b"))
                .approvalHandler(approve())
                .timeout(Duration.ofMillis(150))
                .build();

        // Act
        long started = System.nanoTime();
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(CodeActProgram.ofCommands(slowCommand(5)));

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.TIMED_OUT),
                    () -> assertThat(result.steps()).hasSize(1),
                    () -> assertThat(result.steps().getFirst().timedOut()).isTrue(),
                    () -> assertThat(result.steps().getFirst().exitCode()).isEqualTo(124),
                    () -> assertThat(Duration.ofNanos(System.nanoTime() - started))
                            .isLessThan(Duration.ofSeconds(3)));
        }
    }

    @Test
    void startRun_shouldPropagateCancellationAndTerminateShellProcess() throws Exception {
        // Arrange
        CountDownLatch stepStarted = new CountDownLatch(1);
        CountDownLatch runCancelled = new CountDownLatch(1);
        CodeActOptions options = optionsBuilder(allow("^(sleep|Start-Sleep)\\b"))
                .approvalHandler(approve())
                .timeout(Duration.ofSeconds(30))
                .eventListener(event -> {
                    if (event.type() == CodeActEventType.STEP_STARTED) {
                        stepStarted.countDown();
                    } else if (event.type() == CodeActEventType.RUN_CANCELLED) {
                        runCancelled.countDown();
                    }
                })
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            var handle = executor.startRun(CodeActProgram.ofCommands(slowCommand(30)));
            assertThat(stepStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handle.cancel()).isTrue();

            // Assert
            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            assertThat(runCancelled.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void run_shouldStopAtConfiguredMaximumStepCount() {
        // Arrange
        CodeActOptions options = optionsBuilder(allow("^echo\\b"))
                .approvalHandler(approve())
                .maxSteps(2)
                .build();
        CodeActProgram program =
                CodeActProgram.ofCommands("echo one > one.txt", "echo two > two.txt", "echo three > three.txt");

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(program);

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.MAX_STEPS_REACHED),
                    () -> assertThat(result.steps()).hasSize(2),
                    () -> assertThat(workspace.resolve("one.txt")).exists(),
                    () -> assertThat(workspace.resolve("two.txt")).exists(),
                    () -> assertThat(workspace.resolve("three.txt")).doesNotExist(),
                    () -> assertThat(result.events())
                            .filteredOn(event -> event.type() == CodeActEventType.LIMIT_REACHED)
                            .anySatisfy(event -> assertThat(((StateValue.StringValue)
                                                    event.data().require("limit"))
                                            .value())
                                    .isEqualTo("maxSteps")));
        }
    }

    @Test
    void run_shouldBoundAggregateUtf8Output() {
        // Arrange
        int maximumBytes = 32;
        CodeActOptions options = optionsBuilder(allow("^(printf|Write-Output)\\b"))
                .approvalHandler(approve())
                .maxOutputBytes(maximumBytes)
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(CodeActProgram.ofCommands(longOutputCommand()));
            CodeActStepResult step = result.steps().getFirst();
            int retainedBytes = (step.stdout() + step.stderr()).getBytes(StandardCharsets.UTF_8).length;

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.COMPLETED),
                    () -> assertThat(result.state().capturedOutputBytes()).isLessThanOrEqualTo(maximumBytes),
                    () -> assertThat(retainedBytes).isLessThanOrEqualTo(maximumBytes),
                    () -> assertThat(result.state().outputTruncated()).isTrue(),
                    () -> assertThat(step.truncated()).isTrue());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"cd ..", "echo escaped > ../outside.txt", "cat /etc/passwd", "cat //etc/passwd", "echo $PATH"})
    void run_shouldRejectWorkspaceEscapeBeforeApproval(String command) {
        // Arrange
        AtomicBoolean approvalRequested = new AtomicBoolean();
        CodeActOptions options = optionsBuilder(allow(".*"))
                .approvalHandler((request, cancellation) -> {
                    approvalRequested.set(true);
                    return CompletableFuture.completedFuture(ToolApprovalDecision.approve(request));
                })
                .build();

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult result = executor.run(CodeActProgram.ofCommands(command));

            // Assert
            assertAll(
                    () -> assertThat(result.status()).isEqualTo(CodeActStatus.POLICY_DENIED),
                    () -> assertThat(result.steps()).isEmpty(),
                    () -> assertThat(approvalRequested).isFalse(),
                    () -> assertThat(result.events())
                            .extracting(CodeActEvent::type)
                            .contains(CodeActEventType.POLICY_REJECTED)
                            .doesNotContain(CodeActEventType.APPROVAL_REQUESTED));
        }
    }

    @Test
    void run_shouldProduceDeterministicStateEventsAndTranscript() {
        // Arrange
        CodeActOptions options =
                optionsBuilder(allow("^echo\\b")).approvalHandler(approve()).build();
        CodeActProgram program = CodeActProgram.ofCommands("echo deterministic-one", "echo deterministic-two");

        // Act
        try (CodeActExecutor executor = new CodeActExecutor(options)) {
            CodeActResult first = executor.run(program);
            CodeActResult second = executor.run(program);

            // Assert
            assertAll(
                    () -> assertThat(first.runId()).isEqualTo(second.runId()),
                    () -> assertThat(first.state()).isEqualTo(second.state()),
                    () -> assertThat(first.events()).isEqualTo(second.events()),
                    () -> assertThat(first.transcript()).isEqualTo(second.transcript()),
                    () -> assertThat(first.transcript()).doesNotContain("duration", "timestamp"));
        }
    }

    private CodeActOptions.Builder optionsBuilder(ShellPolicy policy) {
        return CodeActOptions.builder(workspace).shellPolicy(policy);
    }

    private static CodeActApprovalHandler approve() {
        return (request, cancellation) -> CompletableFuture.completedFuture(ToolApprovalDecision.approve(request));
    }

    private static ShellPolicy allow(String expression) {
        return new ShellPolicy(List.of(), List.of(expression), null);
    }

    private static String slowCommand(int seconds) {
        if (isWindows()) {
            return "Start-Sleep -Seconds " + seconds;
        }
        return "sleep " + seconds;
    }

    private static String longOutputCommand() {
        if (isWindows()) {
            return "Write-Output ('x' * 4096)";
        }
        return "printf '%04096d' 0";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
