// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.core.RunCancellation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes model-generated commands on the host operating system.
 *
 * <p>Approval is the security boundary. Persistent mode is single-session and preserves shell
 * state. It must not be shared across users, tenants, or concurrent conversations.
 */
public final class LocalShellExecutor extends ShellExecutor {
    /** Recommended default timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final LocalShellExecutorOptions options;
    private final ResolvedShell shell;
    private final Path workingDirectory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PersistentShellSession persistentSession;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a persistent local executor with safe defaults. */
    public LocalShellExecutor() {
        this(LocalShellExecutorOptions.defaults());
    }

    /**
     * Creates a configured local executor.
     *
     * @param options local execution options
     */
    public LocalShellExecutor(LocalShellExecutorOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        shell = ShellResolver.resolve(options.shellCommand());
        if (options.mode() == ShellMode.PERSISTENT && shell.kind() == ShellKind.CMD) {
            throw new IllegalArgumentException("Persistent mode is not supported for cmd.exe.");
        }
        workingDirectory = Path.of(options.workingDirectory() == null ? "" : options.workingDirectory())
                .toAbsolutePath()
                .normalize();
        persistentSession = options.mode() == ShellMode.PERSISTENT
                ? new PersistentShellSession(
                        shell,
                        workingDirectory,
                        options.environment(),
                        options.removedEnvironmentVariables(),
                        options.cleanEnvironment(),
                        options.maxOutputBytes(),
                        executor)
                : null;
    }

    /**
     * Returns the resolved host shell binary.
     *
     * @return shell binary path or name
     */
    public String resolvedShellBinary() {
        return shell.binary();
    }

    /**
     * Returns the configured execution mode.
     *
     * @return shell mode
     */
    public ShellMode mode() {
        return options.mode();
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<Void> initializeAsync(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ensureOpen();
        if (persistentSession == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> {
                    if (cancellation.isCancellationRequested()) {
                        throw new com.microsoft.agents.core.RunCancelledException();
                    }
                    persistentSession.start();
                },
                executor);
    }

    /** {@inheritDoc} */
    @Override
    protected Duration configuredTimeout() {
        return options.timeout();
    }

    /** {@inheritDoc} */
    @Override
    protected CompletionStage<ShellResult> executeAsync(
            String command, Duration timeout, RunCancellation cancellation) {
        ensureOpen();
        ShellDecision decision = options.policy().evaluate(new ShellRequest(command, workingDirectory.toString()));
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(
                    new ShellCommandRejectedException("Command rejected by policy: " + decision.reason()));
        }
        if (options.commandObserver() != null) {
            try {
                options.commandObserver().accept(command);
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    if (persistentSession != null) {
                        return persistentSession.run(reanchor(command), timeout, cancellation);
                    }
                    return ShellProcesses.run(
                            shell.statelessCommand(command),
                            workingDirectory,
                            options.environment(),
                            options.removedEnvironmentVariables(),
                            options.cleanEnvironment(),
                            timeout,
                            options.maxOutputBytes(),
                            cancellation,
                            executor);
                },
                executor);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean allowsUnapprovedExecution() {
        return options.acknowledgeUnsafe();
    }

    /** {@inheritDoc} */
    @Override
    protected String defaultDescription() {
        if (options.mode() == ShellMode.PERSISTENT) {
            return "Execute one shell command on the local machine and return stdout, stderr, and "
                    + "the exit code. Commands share a persistent session. Approval is required by default.";
        }
        return "Execute one shell command on the local machine and return stdout, stderr, and "
                + "the exit code. Each command uses a fresh process. Approval is required by default.";
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        if (persistentSession != null) {
                            persistentSession.close();
                        }
                    } finally {
                        executor.shutdown();
                    }
                },
                executor);
    }

    private String reanchor(String command) {
        if (!options.confineWorkingDirectory()) {
            return command;
        }
        String directory = workingDirectory.toString();
        if (shell.kind() == ShellKind.POWERSHELL) {
            return "Set-Location -LiteralPath '" + directory.replace("'", "''") + "'; " + command;
        }
        return "cd -- '" + directory.replace("'", "'\\''") + "' && {\n" + command + "\n}";
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("LocalShellExecutor is closed.");
        }
    }
}
