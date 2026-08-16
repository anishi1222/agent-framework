// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes shell commands through a Docker-compatible container runtime.
 *
 * <p>Defaults disable networking, use a non-root user, drop all capabilities, prohibit privilege
 * escalation, bound memory and process count, and mount the root filesystem read-only. These
 * controls are a restrictive baseline rather than a guarantee; approval remains enabled by
 * default, and stronger isolation may be required for adversarial workloads.
 */
public final class DockerShellExecutor extends ShellExecutor {
    /** Default Azure Linux base image. */
    public static final String DEFAULT_IMAGE = "mcr.microsoft.com/azurelinux/base/core:3.0";

    /** Default memory limit of 512 MiB. */
    public static final long DEFAULT_MEMORY_BYTES = 512L * 1024 * 1024;

    /** Default process-count limit. */
    public static final int DEFAULT_PIDS_LIMIT = 256;

    /** Default in-container working directory. */
    public static final String DEFAULT_CONTAINER_WORKING_DIRECTORY = "/workspace";

    /** Recommended default timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final int STATELESS_CLEANUP_ATTEMPTS = 5;

    private final DockerShellExecutorOptions options;
    private final String containerName;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean containerStarted;
    private PersistentShellSession persistentSession;

    /** Creates a persistent container executor with restrictive defaults. */
    public DockerShellExecutor() {
        this(DockerShellExecutorOptions.defaults());
    }

    /**
     * Creates a configured container executor.
     *
     * @param options Docker execution options
     */
    public DockerShellExecutor(DockerShellExecutorOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        containerName = options.containerName() == null
                ? "af-shell-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : options.containerName();
    }

    /**
     * Returns the persistent container name.
     *
     * @return generated or configured name
     */
    public String containerName() {
        return containerName;
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<Void> initializeAsync(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ensureOpen();
        if (options.mode() == ShellMode.STATELESS) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> ensureContainerStarted(cancellation), executor);
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
        ShellDecision decision =
                options.policy().evaluate(new ShellRequest(command, options.containerWorkingDirectory()));
        if (!decision.allowed()) {
            return CompletableFuture.failedFuture(
                    new ShellCommandRejectedException("Command rejected by policy: " + decision.reason()));
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    if (options.mode() == ShellMode.PERSISTENT) {
                        ensureContainerStarted(cancellation);
                        return persistentSession.run(command, timeout, cancellation);
                    }
                    String statelessContainerName = newStatelessContainerName();
                    try {
                        ShellResult result = ShellProcesses.run(
                                buildStatelessRunCommand(options, command, statelessContainerName),
                                Path.of("").toAbsolutePath().normalize(),
                                Map.of(),
                                Set.of(),
                                false,
                                timeout,
                                options.maxOutputBytes(),
                                cancellation,
                                executor);
                        if (result.timedOut()) {
                            removeStatelessContainer(statelessContainerName);
                        }
                        return result;
                    } catch (RuntimeException failure) {
                        try {
                            removeStatelessContainer(statelessContainerName);
                        } catch (RuntimeException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                        throw failure;
                    }
                },
                executor);
    }

    /** {@inheritDoc} */
    @Override
    protected boolean allowsUnapprovedExecution() {
        return true;
    }

    /** {@inheritDoc} */
    @Override
    protected String defaultDescription() {
        return "Execute one shell command inside a restrictive Docker-compatible container and "
                + "return stdout, stderr, and the exit code. Approval is required by default.";
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
                        stopContainer();
                    } finally {
                        executor.shutdown();
                    }
                },
                executor);
    }

    /**
     * Probes whether a Docker-compatible daemon is reachable.
     *
     * @param binary runtime binary
     * @return stage producing {@code true} only after a successful server-version query
     */
    public static CompletionStage<Boolean> isAvailableAsync(String binary) {
        Objects.requireNonNull(binary, "binary");
        ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        ShellResult result = ShellProcesses.run(
                                List.of(binary, "version", "--format", "{{.Server.Version}}"),
                                Path.of("").toAbsolutePath().normalize(),
                                Map.of(),
                                Set.of(),
                                false,
                                Duration.ofSeconds(5),
                                4096,
                                new DefaultRunCancellation(),
                                probeExecutor);
                        return result.exitCode() == 0;
                    } catch (ShellExecutionException exception) {
                        return false;
                    } finally {
                        probeExecutor.shutdown();
                    }
                },
                probeExecutor);
    }

    /**
     * Builds the persistent container-start command.
     *
     * @param options Docker options
     * @param containerName concrete container name
     * @return immutable argument vector
     */
    public static List<String> buildPersistentRunCommand(DockerShellExecutorOptions options, String containerName) {
        ArrayList<String> command = baseRunCommand(options, containerName);
        command.add("-d");
        command.add(options.image());
        command.add("sleep");
        command.add("infinity");
        return List.copyOf(command);
    }

    /**
     * Builds the persistent inner-shell command.
     *
     * @param binary Docker-compatible binary
     * @param containerName concrete container name
     * @return immutable argument vector
     */
    public static List<String> buildExecCommand(String binary, String containerName) {
        return List.of(binary, "exec", "-i", containerName, "bash", "--noprofile", "--norc");
    }

    /**
     * Builds one stateless container command.
     *
     * @param options Docker options
     * @param command shell command
     * @return immutable argument vector
     */
    public static List<String> buildStatelessRunCommand(DockerShellExecutorOptions options, String command) {
        return buildStatelessRunCommand(options, command, null);
    }

    static List<String> buildStatelessRunCommand(
            DockerShellExecutorOptions options, String command, String statelessContainerName) {
        ArrayList<String> result = baseRunCommand(options, statelessContainerName);
        result.add(options.image());
        result.addAll(List.of("bash", "--noprofile", "--norc", "-c", command));
        return List.copyOf(result);
    }

    private static ArrayList<String> baseRunCommand(DockerShellExecutorOptions options, String containerName) {
        ArrayList<String> command = new ArrayList<>();
        command.addAll(List.of(
                options.dockerBinary(),
                "run",
                "--rm",
                "--user",
                options.user().toString(),
                "--network",
                options.network(),
                "--memory",
                options.memoryBytes() + "b",
                "--pids-limit",
                Integer.toString(options.pidsLimit()),
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--tmpfs",
                "/tmp:rw,nosuid,nodev,size=64m",
                "--workdir",
                options.containerWorkingDirectory()));
        if (containerName != null) {
            command.addAll(List.of("--name", containerName));
        }
        if (options.readOnlyRoot()) {
            command.add("--read-only");
        }
        if (options.hostWorkingDirectory() != null) {
            command.add("-v");
            command.add(options.hostWorkingDirectory()
                    + ":"
                    + options.containerWorkingDirectory()
                    + ":"
                    + (options.mountReadOnly() ? "ro" : "rw"));
        }
        options.environment().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> command.addAll(List.of("-e", entry.getKey() + "=" + entry.getValue())));
        command.addAll(options.extraRunArguments());
        return command;
    }

    private void ensureContainerStarted(RunCancellation cancellation) {
        lifecycleLock.lock();
        try {
            ensureOpen();
            if (containerStarted) {
                return;
            }
            ShellResult started = ShellProcesses.run(
                    buildPersistentRunCommand(options, containerName),
                    Path.of("").toAbsolutePath().normalize(),
                    Map.of(),
                    Set.of(),
                    false,
                    options.timeout(),
                    options.maxOutputBytes(),
                    cancellation,
                    executor);
            if (started.exitCode() != 0) {
                throw new ShellExecutionException("Failed to start shell container: " + started.formatForModel());
            }
            ResolvedShell dockerExec = ShellResolver.commandPrefix(
                    buildExecCommand(options.dockerBinary(), containerName), ShellKind.POSIX);
            persistentSession = new PersistentShellSession(
                    dockerExec,
                    Path.of("").toAbsolutePath().normalize(),
                    Map.of(),
                    Set.of(),
                    false,
                    options.maxOutputBytes(),
                    executor);
            persistentSession.start();
            containerStarted = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void stopContainer() {
        lifecycleLock.lock();
        try {
            if (persistentSession != null) {
                persistentSession.close();
                persistentSession = null;
            }
            if (!containerStarted) {
                return;
            }
            ShellResult stopped = ShellProcesses.run(
                    List.of(options.dockerBinary(), "rm", "-f", containerName),
                    Path.of("").toAbsolutePath().normalize(),
                    Map.of(),
                    Set.of(),
                    false,
                    Duration.ofSeconds(10),
                    4096,
                    new DefaultRunCancellation(),
                    executor);
            containerStarted = false;
            if (stopped.exitCode() != 0) {
                throw new ShellExecutionException("Failed to remove shell container: " + stopped.formatForModel());
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private String newStatelessContainerName() {
        return "af-shell-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void removeStatelessContainer(String name) {
        ShellExecutionException lastFailure = null;
        for (int attempt = 1; attempt <= STATELESS_CLEANUP_ATTEMPTS; attempt++) {
            ShellResult removed = ShellProcesses.run(
                    List.of(options.dockerBinary(), "rm", "-f", name),
                    Path.of("").toAbsolutePath().normalize(),
                    Map.of(),
                    Set.of(),
                    false,
                    Duration.ofSeconds(5),
                    4096,
                    new DefaultRunCancellation(),
                    executor);
            if (removed.exitCode() == 0) {
                return;
            }
            lastFailure = new ShellExecutionException(
                    "Failed to remove stateless shell container '" + name + "': " + removed.formatForModel());
            if (attempt < STATELESS_CLEANUP_ATTEMPTS) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ShellExecutionException(
                            "Interrupted while removing stateless shell container '" + name + "'.", exception);
                }
            }
        }
        throw Objects.requireNonNull(lastFailure);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("DockerShellExecutor is closed.");
        }
    }
}
