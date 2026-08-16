// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ShellEnvironmentProviderTest {
    @Test
    void provideAsync_shouldProbeOnceDeduplicateToolsAndInjectInstructions() {
        // Arrange
        FakeShellExecutor executor = new FakeShellExecutor();
        ShellEnvironmentProviderOptions options = ShellEnvironmentProviderOptions.builder()
                .overrideFamily(ShellFamily.POSIX)
                .probeTools(List.of("git", "GIT", "bad;tool", "missing"))
                .build();
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider("shell-test", executor, options);
        ContextProviderRequest request = request();

        // Act
        ContextContribution first =
                provider.provideAsync(request).toCompletableFuture().join();
        ContextContribution second =
                provider.provideAsync(request).toCompletableFuture().join();

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.instructions().getFirst())
                .contains("POSIX shell 5.2")
                .contains("Working directory: /workspace")
                .contains("git (git version 2.50)")
                .contains("Not installed: bad;tool, missing");
        assertThat(executor.initializeCalls).hasValue(1);
        assertThat(executor.commands)
                .containsExactly(
                        "echo \"VERSION=${BASH_VERSION:-${ZSH_VERSION:-unknown}}\"; echo \"CWD=$(pwd)\"",
                        "git --version",
                        "missing --version");
        assertThat(provider.currentSnapshot()).isNotNull();
    }

    @Test
    void formatInstructions_shouldUsePowerShellIdioms() {
        // Arrange
        ShellEnvironmentSnapshot snapshot =
                new ShellEnvironmentSnapshot(ShellFamily.POWERSHELL, "Windows", "7.5", "C:\\repo", orderedVersions());

        // Act
        String instructions = ShellEnvironmentProvider.formatInstructions(snapshot);

        // Assert
        assertThat(instructions)
                .contains("PowerShell 7.5")
                .contains("$env:NAME")
                .contains("Set-Location")
                .contains("Available CLIs: git (2.50)")
                .contains("Not installed: docker");
    }

    private static Map<String, String> orderedVersions() {
        java.util.LinkedHashMap<String, String> versions = new java.util.LinkedHashMap<>();
        versions.put("git", "2.50");
        versions.put("docker", null);
        return versions;
    }

    private static ContextProviderRequest request() {
        AgentSession session = new AgentSession("shell-session");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AgentRunContext runContext = new AgentRunContext(
                "shell-run",
                new AgentMetadata("shell-agent", "Shell agent", "test"),
                Instant.EPOCH,
                List.of(),
                RunOptions.empty(),
                cancellation,
                Map.of(),
                session,
                ContextContribution.empty());
        return new ContextProviderRequest(session, runContext, List.of(), List.of(), Map.of(), List.of());
    }

    private static final class FakeShellExecutor extends ShellExecutor {
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final List<String> commands = new ArrayList<>();

        @Override
        public CompletionStage<Void> initializeAsync(RunCancellation cancellation) {
            initializeCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        protected Duration configuredTimeout() {
            return Duration.ofSeconds(30);
        }

        @Override
        protected CompletionStage<ShellResult> executeAsync(
                String command, Duration timeout, RunCancellation cancellation) {
            commands.add(command);
            if (command.startsWith("echo \"VERSION=")) {
                return completed("VERSION=5.2\nCWD=/workspace\n", "", 0);
            }
            if (command.equals("git --version")) {
                return completed("git version 2.50\n", "", 0);
            }
            return completed("", "not found", 127);
        }

        @Override
        protected boolean allowsUnapprovedExecution() {
            return true;
        }

        @Override
        protected String defaultDescription() {
            return "Fake shell.";
        }

        @Override
        public CompletionStage<Void> closeAsync() {
            return CompletableFuture.completedFuture(null);
        }

        private static CompletionStage<ShellResult> completed(String stdout, String stderr, int exitCode) {
            return CompletableFuture.completedFuture(
                    new ShellResult(stdout, stderr, exitCode, Duration.ZERO, false, false));
        }
    }
}
