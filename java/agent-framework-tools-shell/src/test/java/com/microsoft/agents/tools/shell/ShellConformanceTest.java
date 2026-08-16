// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunContext;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.tools.ToolApprovalMode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @TempDir
    Path temporaryDirectory;

    @Test
    void jcfTools014_shouldBindShellSafetyExecutionAndEnvironmentContracts() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-TOOLS-014");
        ConformanceValue.ObjectValue local = object(fixture.input(), "localDefaults");
        ConformanceValue.ObjectValue docker = object(fixture.input(), "dockerDefaults");
        ConformanceValue.ObjectValue providerInput = object(fixture.input(), "environmentProvider");
        Duration defaultTimeout = Duration.ofSeconds(integer(local, "timeoutSeconds"));
        int defaultOutputBytes = integer(local, "maxOutputBytes");
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        // Act
        LocalShellExecutorOptions statelessOptions = LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .timeout(Duration.ofMillis(150))
                .maxOutputBytes(16)
                .acknowledgeUnsafe(true)
                .build();
        ShellResult exit;
        ShellResult bounded;
        ShellResult timedOut;
        boolean approvalDefault;
        boolean hostOptOutRejected;
        List<String> capabilities;
        try (LocalShellExecutor shell = new LocalShellExecutor(statelessOptions)) {
            exit = shell.run("exit 9");
            bounded = shell.run(
                    windows
                            ? "Write-Output ('abcdefghijklmnopqrstuvwxyz' * 2)"
                            : "printf 'abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz'");
            timedOut = shell.run(windows ? "Start-Sleep -Seconds 5" : "sleep 5");
            approvalDefault = shell.asFunctionTool().metadata().approvalMode() == ToolApprovalMode.ALWAYS_REQUIRE;
            capabilities = shell.asFunctionTool().capabilities().stream()
                    .map(capability -> capability.value())
                    .sorted()
                    .toList();
        }
        try (LocalShellExecutor shell = new LocalShellExecutor(
                LocalShellExecutorOptions.builder().mode(ShellMode.STATELESS).build())) {
            try {
                shell.asFunctionTool("unsafe_shell", "Unsafe host shell.", ToolApprovalMode.NEVER_REQUIRE);
                hostOptOutRejected = false;
            } catch (IllegalStateException exception) {
                hostOptOutRejected = exception.getMessage().contains("acknowledgeUnsafe");
            }
        }

        boolean persistentState;
        try (LocalShellExecutor shell = new LocalShellExecutor(LocalShellExecutorOptions.builder()
                .mode(ShellMode.PERSISTENT)
                .workingDirectory(temporaryDirectory.toString())
                .confineWorkingDirectory(false)
                .build())) {
            shell.run(windows ? "$env:AF_SHELL_CONFORMANCE = 'persisted'" : "export AF_SHELL_CONFORMANCE=persisted");
            ShellResult state =
                    shell.run(windows ? "Write-Output $env:AF_SHELL_CONFORMANCE" : "echo $AF_SHELL_CONFORMANCE");
            persistentState = state.stdout().contains("persisted");
        }

        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        boolean cancellationPropagated;
        try (LocalShellExecutor shell = new LocalShellExecutor(LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .timeout(null)
                .build())) {
            CompletableFuture<ShellResult> running = shell.runAsync(
                            windows ? "Start-Sleep -Seconds 30" : "sleep 30", cancellation)
                    .toCompletableFuture();
            sleep(Duration.ofMillis(100));
            cancellation.cancel();
            try {
                running.join();
                cancellationPropagated = false;
            } catch (CompletionException exception) {
                cancellationPropagated = exception.getCause() instanceof RunCancelledException;
            }
        }

        ShellPolicy policy = new ShellPolicy(List.of("echo"), List.of("^echo"), request -> ShellDecision.allow());
        boolean policyDenyFirst =
                !policy.evaluate(new ShellRequest("echo denied")).allowed();

        DockerShellExecutorOptions dockerOptions = DockerShellExecutorOptions.defaults();
        List<String> dockerCommand =
                DockerShellExecutor.buildStatelessRunCommand(dockerOptions, "printf 'one argument'");
        boolean commandArgumentsNotRetokenized = dockerCommand.getLast().equals("printf 'one argument'");
        boolean dockerDefaultsMatch = dockerOptions.network().equals(string(docker, "network"))
                && dockerOptions.memoryBytes() == integer(docker, "memoryBytes")
                && dockerOptions.pidsLimit() == integer(docker, "pidsLimit")
                && dockerOptions.user().toString().equals(string(docker, "user"))
                && dockerCommand.contains("--read-only")
                && dockerCommand.contains("ALL")
                && dockerCommand.contains("no-new-privileges");
        boolean containerOptOut;
        try (DockerShellExecutor shell = new DockerShellExecutor(
                DockerShellExecutorOptions.builder().mode(ShellMode.STATELESS).build())) {
            containerOptOut = shell.asFunctionTool(
                                    "container_shell",
                                    "Run in a restrictive container.",
                                    ToolApprovalMode.NEVER_REQUIRE)
                            .metadata()
                            .approvalMode()
                    == ToolApprovalMode.NEVER_REQUIRE;
        }

        CountingShellExecutor probeExecutor = new CountingShellExecutor();
        ShellEnvironmentProvider provider = new ShellEnvironmentProvider(
                string(providerInput, "sourceId"),
                probeExecutor,
                ShellEnvironmentProviderOptions.builder()
                        .overrideFamily(ShellFamily.POSIX)
                        .probeTools(strings(providerInput, "probeTools"))
                        .probeTimeout(Duration.ofSeconds(integer(providerInput, "probeTimeoutSeconds")))
                        .build());
        ContextProviderRequest request = contextRequest();
        ContextContribution first =
                provider.provideAsync(request).toCompletableFuture().join();
        ContextContribution second =
                provider.provideAsync(request).toCompletableFuture().join();
        boolean environmentCached = first.equals(second)
                && probeExecutor.initializeCalls.get() == 1
                && first.instructions().getFirst().contains("Shell environment");

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("toolCapabilities", strings(capabilities));
        actual.put("timeoutExitCode", number(timedOut.exitCode()));
        actual.put("statelessExitCodePropagated", bool(exit.exitCode() == 9));
        actual.put("persistentStatePreserved", bool(persistentState));
        actual.put(
                "headTailOutputBounded",
                bool(bounded.truncated()
                        && bounded.stdout().contains("truncated")
                        && bounded.stdout().contains("abcd")
                        && bounded.stdout().contains("wxyz")));
        actual.put(
                "approvalRequiredByDefault",
                bool(approvalDefault
                        && string(local, "approvalMode").equals(ToolApprovalMode.ALWAYS_REQUIRE.value())
                        && string(local, "mode").equals("persistent")
                        && LocalShellExecutorOptions.defaults().timeout().equals(defaultTimeout)
                        && defaultOutputBytes == 65536));
        actual.put("hostOptOutRequiresAcknowledgement", bool(hostOptOutRejected));
        actual.put("containerOptOutExplicit", bool(containerOptOut && dockerDefaultsMatch));
        actual.put("cancellationPropagated", bool(cancellationPropagated));
        actual.put("policyDenyFirst", bool(policyDenyFirst));
        actual.put("commandArgumentsNotRetokenized", bool(commandArgumentsNotRetokenized));
        actual.put("environmentInstructionsCached", bool(environmentCached));

        // Assert
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static ContextProviderRequest contextRequest() {
        AgentSession session = new AgentSession("shell-conformance");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AgentRunContext runContext = new AgentRunContext(
                "shell-conformance-run",
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

    private static ConformanceValue.ObjectValue object(ConformanceValue.ObjectValue value, String name) {
        return (ConformanceValue.ObjectValue) value.require(name);
    }

    private static String string(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.StringValue) value.require(name)).value();
    }

    private static int integer(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.NumberValue) value.require(name)).value().intValueExact();
    }

    private static List<String> strings(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.ArrayValue) value.require(name))
                .values().stream()
                        .map(ConformanceValue.StringValue.class::cast)
                        .map(ConformanceValue.StringValue::value)
                        .toList();
    }

    private static ConformanceValue.ArrayValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private static ConformanceValue.NumberValue number(int value) {
        return new ConformanceValue.NumberValue(BigDecimal.valueOf(value));
    }

    private static ConformanceValue.BooleanValue bool(boolean value) {
        return new ConformanceValue.BooleanValue(value);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class CountingShellExecutor extends ShellExecutor {
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
            return completed(command.substring(0, command.indexOf(' ')) + " 1.0\n", "", 0);
        }

        @Override
        protected boolean allowsUnapprovedExecution() {
            return true;
        }

        @Override
        protected String defaultDescription() {
            return "Counting shell.";
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
