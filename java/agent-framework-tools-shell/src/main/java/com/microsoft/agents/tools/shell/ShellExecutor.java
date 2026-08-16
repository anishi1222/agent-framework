// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.SynchronousExecutionException;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolUserException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Runs shell commands behind a framework-owned asynchronous and approval-aware contract.
 *
 * <p>Persistent implementations are single-session resources. Do not share one persistent
 * executor between users or concurrent conversations because working-directory, environment, and
 * filesystem state are intentionally retained.
 */
public abstract class ShellExecutor implements AutoCloseable {
    /** Default function name surfaced to models. */
    public static final String DEFAULT_TOOL_NAME = "run_shell";

    /**
     * Eagerly initializes the execution backend.
     *
     * @param cancellation cancellation signal
     * @return completion stage
     */
    public abstract CompletionStage<Void> initializeAsync(RunCancellation cancellation);

    /**
     * Eagerly initializes the execution backend with a new cancellation signal.
     *
     * @return completion stage
     */
    public final CompletionStage<Void> initializeAsync() {
        return initializeAsync(new DefaultRunCancellation());
    }

    /** Eagerly initializes the execution backend synchronously. */
    public final void initialize() {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        await(initializeAsync(cancellation), cancellation, "Shell initialization");
    }

    /**
     * Runs one command using the executor's configured timeout.
     *
     * @param command complete command text
     * @param cancellation cancellation signal
     * @return command result stage
     */
    public final CompletionStage<ShellResult> runAsync(String command, RunCancellation cancellation) {
        return executeAsync(
                requireCommand(command), configuredTimeout(), Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Runs one command with an explicit timeout override.
     *
     * @param command complete command text
     * @param timeout positive timeout, or {@code null} to disable timeout
     * @param cancellation cancellation signal
     * @return command result stage
     */
    public final CompletionStage<ShellResult> runAsync(String command, Duration timeout, RunCancellation cancellation) {
        validateTimeout(timeout);
        return executeAsync(requireCommand(command), timeout, Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Runs one command using a new cancellation signal.
     *
     * @param command complete command text
     * @return command result stage
     */
    public final CompletionStage<ShellResult> runAsync(String command) {
        return runAsync(command, new DefaultRunCancellation());
    }

    /**
     * Runs one command synchronously.
     *
     * @param command complete command text
     * @return command result
     */
    public final ShellResult run(String command) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        return await(runAsync(command, cancellation), cancellation, "Shell command");
    }

    /**
     * Creates a default approval-gated function tool.
     *
     * @return shell function tool
     */
    public final FunctionTool asFunctionTool() {
        return asFunctionTool(DEFAULT_TOOL_NAME, defaultDescription(), ToolApprovalMode.ALWAYS_REQUIRE);
    }

    /**
     * Creates a function tool bound to this executor.
     *
     * @param name unique function name
     * @param description model-facing description
     * @param approvalMode approval policy
     * @return shell function tool
     */
    public final FunctionTool asFunctionTool(String name, String description, ToolApprovalMode approvalMode) {
        requireNonBlank(name, "name");
        requireNonBlank(description, "description");
        Objects.requireNonNull(approvalMode, "approvalMode");
        if (approvalMode == ToolApprovalMode.NEVER_REQUIRE && !allowsUnapprovedExecution()) {
            throw new IllegalStateException("Unapproved local shell execution requires acknowledgeUnsafe=true.");
        }

        ToolMetadata metadata = new ToolMetadata(
                name,
                description,
                Set.of(ToolCapability.FUNCTION, ToolCapability.SHELL),
                approvalMode,
                inputSchema(),
                StateValue.object(Map.of("type", StateValue.string("string"))));
        return FunctionTool.create(metadata, (context, arguments) -> {
            StateValue commandValue = arguments.values().get("command");
            if (!(commandValue instanceof StateValue.StringValue stringValue)) {
                throw new ToolUserException("command must be a string.");
            }
            return runAsync(stringValue.value(), context.cancellation()).handle((result, failure) -> {
                if (failure == null) {
                    return StateValue.string(result.formatForModel());
                }
                Throwable cause = RunHandles.unwrap(failure);
                if (cause instanceof ShellCommandRejectedException rejected) {
                    throw new ToolUserException(rejected.getMessage(), rejected);
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CompletionException(cause);
            });
        });
    }

    /**
     * Releases backend resources asynchronously.
     *
     * @return completion stage
     */
    public abstract CompletionStage<Void> closeAsync();

    /** Releases backend resources synchronously. */
    @Override
    public final void close() {
        await(closeAsync(), new DefaultRunCancellation(), "Shell shutdown");
    }

    /**
     * Returns the configured timeout used by the default run overload.
     *
     * @return positive timeout, or {@code null} when disabled
     */
    protected abstract Duration configuredTimeout();

    /**
     * Executes a validated command.
     *
     * @param command non-empty command text
     * @param timeout timeout override, or {@code null}
     * @param cancellation cancellation signal
     * @return command result stage
     */
    protected abstract CompletionStage<ShellResult> executeAsync(
            String command, Duration timeout, RunCancellation cancellation);

    /**
     * Reports whether this executor permits explicit approval opt-out.
     *
     * @return {@code true} when {@link ToolApprovalMode#NEVER_REQUIRE} is permitted
     */
    protected abstract boolean allowsUnapprovedExecution();

    /**
     * Returns the mode-specific model-facing description.
     *
     * @return non-blank description
     */
    protected abstract String defaultDescription();

    private static StateValue.ObjectValue inputSchema() {
        LinkedHashMap<String, StateValue> command = new LinkedHashMap<>();
        command.put("type", StateValue.string("string"));
        command.put("description", StateValue.string("The shell command to execute."));
        LinkedHashMap<String, StateValue> schema = new LinkedHashMap<>();
        schema.put("type", StateValue.string("object"));
        schema.put("properties", StateValue.object(Map.of("command", StateValue.object(command))));
        schema.put("required", StateValue.array(java.util.List.of(StateValue.string("command"))));
        schema.put("additionalProperties", StateValue.bool(false));
        return StateValue.object(schema);
    }

    private static String requireCommand(String command) {
        return requireNonBlank(command, "command");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive when present.");
        }
    }

    private static <T> T await(CompletionStage<T> stage, DefaultRunCancellation cancellation, String operation) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            cancellation.cancel();
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException(operation + " was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = RunHandles.unwrap(exception.getCause());
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new ShellExecutionException(operation + " failed.", cause);
        }
    }
}
