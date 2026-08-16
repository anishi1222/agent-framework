// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalShellExecutorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void run_shouldCaptureStatelessOutputExitCodeEnvironmentAndTruncation() {
        // Arrange
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .workingDirectory(temporaryDirectory.toString())
                .environment(Map.of("AF_SHELL_VALUE", "present"))
                .maxOutputBytes(16)
                .acknowledgeUnsafe(true)
                .build();

        // Act
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            ShellResult result = shell.run("printf '%s-abcdefghijklmnopqrstuvwxyz' \"$AF_SHELL_VALUE\"");

            // Assert
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("present-").contains("uvwxyz");
            assertThat(result.truncated()).isTrue();
            assertThat(result.timedOut()).isFalse();
        }
    }

    @Test
    void run_shouldReturnExitCodeAndBoundTimeout() {
        // Arrange
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .timeout(Duration.ofMillis(150))
                .acknowledgeUnsafe(true)
                .build();

        // Act and assert
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            assertThat(shell.run("exit 7").exitCode()).isEqualTo(7);
            ShellResult timedOut = shell.run("sleep 5");
            assertThat(timedOut.timedOut()).isTrue();
            assertThat(timedOut.exitCode()).isEqualTo(124);
            assertThat(timedOut.duration()).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void run_shouldRejectPolicyBeforeStartingProcess() {
        // Arrange
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .policy(new ShellPolicy(List.of("\\brm\\s+-rf\\s+/"), null, null))
                .build();

        // Act and assert
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            assertThatThrownBy(() -> shell.run("rm -rf /"))
                    .isInstanceOf(ShellCommandRejectedException.class)
                    .hasMessageContaining("deny pattern");
        }
    }

    @Test
    void persistentMode_shouldPreserveStateWhenConfinementIsDisabled() {
        // Arrange
        Path child = temporaryDirectory.resolve("child");
        child.toFile().mkdir();
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.PERSISTENT)
                .workingDirectory(temporaryDirectory.toString())
                .confineWorkingDirectory(false)
                .build();

        // Act
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            shell.run("export AF_PERSISTED=value");
            shell.run("cd '" + child + "'");
            ShellResult state = shell.run("printf '%s\\n%s' \"$AF_PERSISTED\" \"$(pwd)\"");

            // Assert
            assertThat(state.exitCode()).isZero();
            assertThat(state.stdout()).contains("value");
            assertThat(Path.of(state.stdout().lines().toList().getLast()).toRealPath())
                    .isEqualTo(child.toRealPath());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void persistentMode_shouldReanchorWorkingDirectoryByDefault() {
        // Arrange
        Path child = temporaryDirectory.resolve("child");
        child.toFile().mkdir();
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.PERSISTENT)
                .workingDirectory(temporaryDirectory.toString())
                .build();

        // Act
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            shell.run("cd '" + child + "'");
            ShellResult result = shell.run("pwd");

            // Assert
            assertThat(Path.of(result.stdout()).toRealPath()).isEqualTo(temporaryDirectory.toRealPath());
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void persistentMode_shouldSerializeConcurrentCommandsOnOneProcess() {
        // Arrange
        LocalShellExecutorOptions options =
                LocalShellExecutorOptions.builder().mode(ShellMode.PERSISTENT).build();

        // Act
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            CompletableFuture<ShellResult> first = shell.runAsync("echo $$").toCompletableFuture();
            CompletableFuture<ShellResult> second = shell.runAsync("echo $$").toCompletableFuture();

            // Assert
            assertThat(first.join().stdout().trim())
                    .isEqualTo(second.join().stdout().trim());
        }
    }

    @Test
    void runAsync_shouldPropagateCancellationAndTerminateProcess() throws Exception {
        // Arrange
        LocalShellExecutorOptions options = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .timeout(null)
                .build();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        try (LocalShellExecutor shell = new LocalShellExecutor(options)) {
            CompletableFuture<ShellResult> result =
                    shell.runAsync("sleep 30", cancellation).toCompletableFuture();
            Thread.sleep(100);
            cancellation.cancel();

            // Assert
            assertThatThrownBy(result::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
        }
    }

    @Test
    void asFunctionTool_shouldRequireApprovalAndUnsafeAcknowledgement() {
        // Arrange
        LocalShellExecutorOptions safeOptions =
                LocalShellExecutorOptions.builder().mode(ShellMode.STATELESS).build();

        // Act and assert
        try (LocalShellExecutor shell = new LocalShellExecutor(safeOptions)) {
            assertThat(shell.asFunctionTool().metadata().approvalMode()).isEqualTo(ToolApprovalMode.ALWAYS_REQUIRE);
            assertThat(shell.asFunctionTool().capabilities())
                    .containsExactlyInAnyOrder(ToolCapability.FUNCTION, ToolCapability.SHELL);
            assertThatThrownBy(() ->
                            shell.asFunctionTool("unsafe_shell", "Unsafe host shell.", ToolApprovalMode.NEVER_REQUIRE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("acknowledgeUnsafe");
        }

        LocalShellExecutorOptions acknowledged = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .acknowledgeUnsafe(true)
                .build();
        try (LocalShellExecutor shell = new LocalShellExecutor(acknowledged)) {
            assertThat(shell.asFunctionTool("unsafe_shell", "Unsafe host shell.", ToolApprovalMode.NEVER_REQUIRE)
                            .metadata()
                            .approvalMode())
                    .isEqualTo(ToolApprovalMode.NEVER_REQUIRE);
        }
    }
}
