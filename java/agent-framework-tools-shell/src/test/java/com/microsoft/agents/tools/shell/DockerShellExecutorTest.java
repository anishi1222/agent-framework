// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.tools.ToolApprovalMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerShellExecutorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildPersistentRunCommand_shouldEmitRestrictiveDefaults() {
        // Arrange
        DockerShellExecutorOptions options = DockerShellExecutorOptions.builder()
                .image("example/shell:1")
                .environment(Map.of("MODE", "test"))
                .build();

        // Act
        List<String> command = DockerShellExecutor.buildPersistentRunCommand(options, "af-shell-test");

        // Assert
        assertThat(command)
                .startsWith("docker", "run", "--rm")
                .containsSubsequence("--network", "none")
                .containsSubsequence("--cap-drop", "ALL")
                .containsSubsequence("--security-opt", "no-new-privileges")
                .contains("--read-only")
                .containsSubsequence("--user", "65534:65534")
                .containsSubsequence("-e", "MODE=test")
                .endsWith("-d", "example/shell:1", "sleep", "infinity");
    }

    @Test
    void buildStatelessRunCommand_shouldKeepCommandAsOneArgument() {
        // Arrange
        DockerShellExecutorOptions options = DockerShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .hostWorkingDirectory("/host/project")
                .mountReadOnly(true)
                .build();

        // Act
        List<String> command = DockerShellExecutor.buildStatelessRunCommand(options, "printf 'hello world'");

        // Assert
        assertThat(command)
                .containsSubsequence("-v", "/host/project:/workspace:ro")
                .endsWith("bash", "--noprofile", "--norc", "-c", "printf 'hello world'");
    }

    @Test
    void statelessTimeout_shouldForceRemoveNamedContainer() throws Exception {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path log = temporaryDirectory.resolve("docker.log");
        Path runtime = temporaryDirectory.resolve("fake-docker");
        String logPath = log.toString().replace("'", "'\"'\"'");
        Files.writeString(
                runtime,
                "#!/bin/sh\n"
                        + "printf '%s\\n' \"$*\" >> '"
                        + logPath
                        + "'\n"
                        + "if [ \"$1\" = \"run\" ]; then sleep 30; fi\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwx------"));
        DockerShellExecutorOptions options = DockerShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .dockerBinary(runtime.toString())
                .timeout(Duration.ofMillis(100))
                .build();

        try (DockerShellExecutor shell = new DockerShellExecutor(options)) {
            assertThat(shell.run("printf ignored").timedOut()).isTrue();
        }

        assertThat(Files.readAllLines(log, StandardCharsets.UTF_8))
                .anyMatch(line -> line.contains("run --rm") && line.contains("--name af-shell-run-"))
                .anyMatch(line -> line.matches("rm -f af-shell-run-[a-f0-9]{16}"));
    }

    @Test
    void runAsync_shouldRejectPolicyBeforeInvokingDocker() {
        // Arrange
        DockerShellExecutorOptions options = DockerShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .dockerBinary("definitely-not-a-runtime")
                .policy(new ShellPolicy(List.of("blocked"), null, null))
                .build();

        // Act and assert
        try (DockerShellExecutor shell = new DockerShellExecutor(options)) {
            assertThatThrownBy(() -> shell.run("blocked")).isInstanceOf(ShellCommandRejectedException.class);
        }
    }

    @Test
    void asFunctionTool_shouldDefaultToApprovalAndPermitExplicitContainerOptOut() {
        // Arrange
        DockerShellExecutorOptions options =
                DockerShellExecutorOptions.builder().mode(ShellMode.STATELESS).build();

        // Act and assert
        try (DockerShellExecutor shell = new DockerShellExecutor(options)) {
            assertThat(shell.asFunctionTool().metadata().approvalMode()).isEqualTo(ToolApprovalMode.ALWAYS_REQUIRE);
            assertThat(shell.asFunctionTool(
                                    "container_shell",
                                    "Run in a restrictive container.",
                                    ToolApprovalMode.NEVER_REQUIRE)
                            .metadata()
                            .approvalMode())
                    .isEqualTo(ToolApprovalMode.NEVER_REQUIRE);
        }
    }

    @Test
    void isAvailableAsync_shouldReturnFalseForMissingBinary() {
        // Act
        boolean available = DockerShellExecutor.isAvailableAsync("definitely-not-a-runtime-xyz")
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(available).isFalse();
    }
}
