// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Adds authoritative shell syntax and installed-CLI guidance to each agent run.
 *
 * <p>The first successful probe is cached for the provider lifetime. Expected command rejection,
 * launch failure, non-zero exit, and probe timeout produce missing snapshot fields; caller
 * cancellation and unexpected failures propagate.
 */
public final class ShellEnvironmentProvider implements ContextProvider {
    /** Default stable context-provider identifier. */
    public static final String DEFAULT_ID = "shell_environment";

    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private final String id;
    private final ShellExecutor executor;
    private final ShellEnvironmentProviderOptions options;
    private final AtomicReference<CompletableFuture<ShellEnvironmentSnapshot>> cached = new AtomicReference<>();
    private volatile ShellEnvironmentSnapshot currentSnapshot;

    /**
     * Creates a provider with default options and identifier.
     *
     * @param executor shell executor used for probes
     */
    public ShellEnvironmentProvider(ShellExecutor executor) {
        this(DEFAULT_ID, executor, ShellEnvironmentProviderOptions.defaults());
    }

    /**
     * Creates a configured provider.
     *
     * @param id stable provider identifier
     * @param executor shell executor used for probes
     * @param options probe options
     */
    public ShellEnvironmentProvider(String id, ShellExecutor executor, ShellEnvironmentProviderOptions options) {
        this.id = requireNonBlank(id, "id");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** {@inheritDoc} */
    @Override
    public String id() {
        return id;
    }

    /**
     * Returns the most recently completed snapshot.
     *
     * @return snapshot, or {@code null} before the first successful probe
     */
    public ShellEnvironmentSnapshot currentSnapshot() {
        return currentSnapshot;
    }

    /**
     * Forces a fresh probe and replaces the cache.
     *
     * @param cancellation cancellation signal
     * @return fresh snapshot stage
     */
    public CompletionStage<ShellEnvironmentSnapshot> refreshAsync(RunCancellation cancellation) {
        CompletableFuture<ShellEnvironmentSnapshot> future =
                probeAsync(Objects.requireNonNull(cancellation, "cancellation")).toCompletableFuture();
        cached.set(future);
        observeCache(future);
        return future.minimalCompletionStage();
    }

    /** {@inheritDoc} */
    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        Objects.requireNonNull(request, "request");
        return getOrProbe(request.runContext().cancellation()).thenApply(snapshot -> {
            String instructions = options.instructionsFormatter() == null
                    ? formatInstructions(snapshot)
                    : options.instructionsFormatter().apply(snapshot);
            if (instructions == null || instructions.isBlank()) {
                throw new IllegalStateException("Shell environment instructions must not be blank.");
            }
            return new ContextContribution(List.of(instructions), List.of(), Map.of(), List.of());
        });
    }

    /**
     * Formats a snapshot as a deterministic shell-instructions block.
     *
     * @param snapshot shell snapshot
     * @return markdown-style instructions
     */
    public static String formatInstructions(ShellEnvironmentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder result = new StringBuilder("## Shell environment\n");
        String version = snapshot.shellVersion() == null ? "" : " " + snapshot.shellVersion();
        if (snapshot.family() == ShellFamily.POWERSHELL) {
            result.append("You are operating a PowerShell")
                    .append(version)
                    .append(" session on ")
                    .append(snapshot.operatingSystem())
                    .append(".\n")
                    .append("Use PowerShell idioms, NOT bash:\n")
                    .append("- Set environment variables with `$env:NAME = 'value'`.\n")
                    .append("- Reference environment variables as `$env:NAME`.\n")
                    .append("- Use `Set-Location` or `cd`; paths normally use `\\` separators.\n")
                    .append("- Use `Out-Null` instead of `/dev/null`.");
        } else {
            result.append("You are operating a POSIX shell")
                    .append(version)
                    .append(" session on ")
                    .append(snapshot.operatingSystem())
                    .append(".\n")
                    .append("Use POSIX shell idioms (bash/sh).\n")
                    .append("- Set environment variables with `export NAME=value`.\n")
                    .append("- Reference environment variables as `$NAME` or `${NAME}`.\n")
                    .append("- Paths use `/` separators.");
        }
        if (!snapshot.workingDirectory().isEmpty()) {
            result.append("\nWorking directory: ").append(snapshot.workingDirectory());
        }
        List<String> installed = snapshot.toolVersions().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();
        List<String> missing = snapshot.toolVersions().entrySet().stream()
                .filter(entry -> entry.getValue() == null)
                .map(Map.Entry::getKey)
                .toList();
        if (!installed.isEmpty()) {
            result.append("\nAvailable CLIs: ").append(String.join(", ", installed));
        }
        if (!missing.isEmpty()) {
            result.append("\nNot installed: ").append(String.join(", ", missing));
        }
        return result.toString();
    }

    private CompletionStage<ShellEnvironmentSnapshot> getOrProbe(RunCancellation cancellation) {
        while (true) {
            CompletableFuture<ShellEnvironmentSnapshot> existing = cached.get();
            if (existing != null) {
                return existing.minimalCompletionStage();
            }
            CompletableFuture<ShellEnvironmentSnapshot> created =
                    probeAsync(cancellation).toCompletableFuture();
            if (cached.compareAndSet(null, created)) {
                observeCache(created);
                return created.minimalCompletionStage();
            }
        }
    }

    private void observeCache(CompletableFuture<ShellEnvironmentSnapshot> future) {
        future.whenComplete((snapshot, failure) -> {
            if (failure == null) {
                currentSnapshot = snapshot;
            } else {
                cached.compareAndSet(future, null);
            }
        });
    }

    private CompletionStage<ShellEnvironmentSnapshot> probeAsync(RunCancellation cancellation) {
        ShellFamily family = options.overrideFamily() == null ? detectFamily() : options.overrideFamily();
        return executor.initializeAsync(cancellation)
                .thenCompose(ignored -> runProbe(shellProbe(family), cancellation))
                .thenCompose(shellResult -> {
                    ShellIdentity identity = parseIdentity(shellResult);
                    LinkedHashMap<String, String> versions = new LinkedHashMap<>();
                    CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    for (String tool : options.probeTools()) {
                        if (!seen.add(tool.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        sequence = sequence.thenCompose(ignored ->
                                probeTool(tool, cancellation).thenAccept(version -> versions.put(tool, version)));
                    }
                    return sequence.thenApply(ignored -> new ShellEnvironmentSnapshot(
                            family,
                            operatingSystemDescription(),
                            identity.version(),
                            identity.workingDirectory(),
                            versions));
                });
    }

    private CompletionStage<String> probeTool(String tool, RunCancellation cancellation) {
        if (!TOOL_NAME.matcher(tool).matches()) {
            return CompletableFuture.completedFuture(null);
        }
        return runProbe(tool + " --version", cancellation).thenApply(result -> {
            if (result == null || result.exitCode() != 0) {
                return null;
            }
            String line = firstNonEmptyLine(result.stdout());
            return line == null ? firstNonEmptyLine(result.stderr()) : line;
        });
    }

    private CompletionStage<ShellResult> runProbe(String command, RunCancellation cancellation) {
        return executor.runAsync(command, options.probeTimeout(), cancellation).handle((result, failure) -> {
            if (failure == null) {
                return result.timedOut() ? null : result;
            }
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            if (cause instanceof ShellCommandRejectedException || cause instanceof ShellExecutionException) {
                return null;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new java.util.concurrent.CompletionException(cause);
        });
    }

    private static ShellIdentity parseIdentity(ShellResult result) {
        if (result == null) {
            return new ShellIdentity(null, "");
        }
        String version = null;
        String directory = "";
        for (String line : result.stdout().split("\\R")) {
            if (line.startsWith("VERSION=")) {
                String value = line.substring("VERSION=".length()).trim();
                version = value.isEmpty() || "unknown".equals(value) ? null : value;
            } else if (line.startsWith("CWD=")) {
                directory = line.substring("CWD=".length()).trim();
            }
        }
        return new ShellIdentity(version, directory);
    }

    private static String shellProbe(ShellFamily family) {
        if (family == ShellFamily.POWERSHELL) {
            return "Write-Output (\"VERSION=\" + $PSVersionTable.PSVersion.ToString()); "
                    + "Write-Output (\"CWD=\" + (Get-Location).Path)";
        }
        return "echo \"VERSION=${BASH_VERSION:-${ZSH_VERSION:-unknown}}\"; echo \"CWD=$(pwd)\"";
    }

    private static ShellFamily detectFamily() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? ShellFamily.POWERSHELL
                : ShellFamily.POSIX;
    }

    private static String operatingSystemDescription() {
        return System.getProperty("os.name", "unknown")
                + " "
                + System.getProperty("os.version", "unknown")
                + " "
                + System.getProperty("os.arch", "unknown");
    }

    private static String firstNonEmptyLine(String value) {
        for (String line : value.split("\\R")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return null;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private record ShellIdentity(String version, String workingDirectory) {}
}
