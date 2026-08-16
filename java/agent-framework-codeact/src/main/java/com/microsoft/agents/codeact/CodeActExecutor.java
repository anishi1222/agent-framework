// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolApprovalRequest;
import com.microsoft.agents.tools.ToolApprovalState;
import com.microsoft.agents.tools.ToolApprovals;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolInvocationContext;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.shell.LocalShellExecutor;
import com.microsoft.agents.tools.shell.LocalShellExecutorOptions;
import com.microsoft.agents.tools.shell.ShellCommandRejectedException;
import com.microsoft.agents.tools.shell.ShellDecision;
import com.microsoft.agents.tools.shell.ShellMode;
import com.microsoft.agents.tools.shell.ShellRequest;
import com.microsoft.agents.tools.shell.ShellResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes approved bounded CodeAct programs through {@link LocalShellExecutor}.
 *
 * <p>This executor owns no process-launch implementation. Every submitted step is delegated to the
 * approval-aware framework shell module in stateless mode with a clean environment, an anchored
 * workspace, caller policy, timeout, cancellation, and bounded output. The mandatory workspace
 * command checks and caller policy are defense in depth, not a security sandbox.
 */
public final class CodeActExecutor implements AutoCloseable {
    /** Stable model-facing operation name used to bind approval authority. */
    public static final String EXECUTE_CODE_TOOL_NAME = "execute_code";

    private final CodeActOptions options;
    private final WorkspaceCommandPolicy workspacePolicy;
    private final LocalShellExecutor shell;
    private final FunctionTool approvalTool;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a configured bounded CodeAct executor.
     *
     * @param options immutable options with explicit approval and caller policy
     */
    public CodeActExecutor(CodeActOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        workspacePolicy = new WorkspaceCommandPolicy(options.workspaceRoot(), options.shellPolicy());
        shell = new LocalShellExecutor(shellOptions(options, workspacePolicy));
        approvalTool = approvalTool();
    }

    /**
     * Returns the immutable executor options.
     *
     * @return options snapshot
     */
    public CodeActOptions options() {
        return options;
    }

    /**
     * Starts one program with a framework-owned cancellation signal.
     *
     * @param program immutable ordered program
     * @return cancellable run handle
     */
    public RunHandle<CodeActResult> startRun(CodeActProgram program) {
        return startRun(program, new DefaultRunCancellation());
    }

    /**
     * Starts one program with caller-owned cancellation.
     *
     * @param program immutable ordered program
     * @param cancellation caller-owned cancellation signal
     * @return cancellable run handle
     */
    public RunHandle<CodeActResult> startRun(CodeActProgram program, RunCancellation cancellation) {
        ensureOpen();
        CodeActProgram safeProgram = Objects.requireNonNull(program, "program");
        RunHandleSource<CodeActResult> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        CompletableFuture.runAsync(() -> execute(source, safeProgram), executor);
        return source.handle();
    }

    /**
     * Runs one program asynchronously.
     *
     * @param program immutable ordered program
     * @return terminal result stage
     */
    public CompletionStage<CodeActResult> runAsync(CodeActProgram program) {
        return startRun(program).resultAsync();
    }

    /**
     * Runs one program asynchronously with caller-owned cancellation.
     *
     * @param program immutable ordered program
     * @param cancellation caller-owned cancellation signal
     * @return terminal result stage
     */
    public CompletionStage<CodeActResult> runAsync(CodeActProgram program, RunCancellation cancellation) {
        return startRun(program, cancellation).resultAsync();
    }

    /**
     * Runs one program synchronously.
     *
     * @param program immutable ordered program
     * @return terminal result
     */
    public CodeActResult run(CodeActProgram program) {
        return RunHandles.await(startRun(program), "CodeAct run");
    }

    /**
     * Releases the owned shell runtime and worker executor.
     *
     * <p>Callers should cancel and await active runs before closing.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            shell.close();
        } finally {
            executor.shutdown();
        }
    }

    private void execute(RunHandleSource<CodeActResult> source, CodeActProgram program) {
        String programDigest = CodeActDigests.programDigest(options, program);
        String runId = CodeActDigests.runId(programDigest);
        Execution execution = new Execution(source.cancellation(), program, programDigest, runId);
        try {
            source.tryComplete(execution.run());
        } catch (RunCancelledException cancelled) {
            execution.emitCancellation();
            source.cancellation().cancel();
        } catch (Throwable failure) {
            source.tryFail(failure);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("CodeActExecutor is closed.");
        }
    }

    private final class Execution {
        private final RunCancellation cancellation;
        private final CodeActProgram program;
        private final String programDigest;
        private final String runId;
        private final long startedNanos = System.nanoTime();
        private final long timeoutNanos = options.timeout().toNanos();
        private final CodeActEventLog eventLog;
        private final CodeActOutputBudget outputBudget;
        private final ArrayList<CodeActStepResult> stepResults = new ArrayList<>();
        private boolean outputLimitEmitted;
        private boolean cancellationEmitted;

        private Execution(RunCancellation cancellation, CodeActProgram program, String programDigest, String runId) {
            this.cancellation = cancellation;
            this.program = program;
            this.programDigest = programDigest;
            this.runId = runId;
            eventLog = new CodeActEventLog(runId, options.eventListeners());
            outputBudget = new CodeActOutputBudget(options.maxOutputBytes());
        }

        private CodeActResult run() {
            requireActive();
            eventLog.emit(
                    CodeActEventType.RUN_STARTED,
                    values(
                            "programDigest", StateValue.string(programDigest),
                            "workspace",
                                    StateValue.string(options.workspaceRoot().toString()),
                            "stepCount", StateValue.integer(program.steps().size()),
                            "maxSteps", StateValue.integer(options.maxSteps()),
                            "maxOutputBytes", StateValue.integer(options.maxOutputBytes()),
                            "timeoutMillis",
                                    StateValue.integer(options.timeout().toMillis())));

            CodeActResult policyResult = preflightPolicy();
            if (policyResult != null) {
                return policyResult;
            }

            ToolApprovalRequest request = approvalRequest();
            eventLog.emit(
                    CodeActEventType.APPROVAL_REQUESTED,
                    values(
                            "approvalId", StateValue.string(request.approvalId().value()),
                            "requestDigest", StateValue.string(request.requestDigest())));

            ToolApprovalDecision decision;
            try {
                decision = awaitApproval(requestApprovalAsync(request));
            } catch (ApprovalTimedOutException ignored) {
                return finish(CodeActStatus.TIMED_OUT, "Approval did not complete before the timeout.");
            } catch (RunCancelledException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                return finish(CodeActStatus.FAILED, "Approval handler failed: " + failureDescription(failure));
            }
            validateDecision(request, decision);
            if (decision.state() == ToolApprovalState.REJECTED) {
                String reason = decision.reason() == null ? "Approval was denied." : decision.reason();
                eventLog.emit(CodeActEventType.APPROVAL_DENIED, values("reason", StateValue.string(reason)));
                return finish(CodeActStatus.APPROVAL_DENIED, reason);
            }
            eventLog.emit(CodeActEventType.APPROVAL_GRANTED, Map.of());

            int executed = Math.min(program.steps().size(), options.maxSteps());
            for (int index = 0; index < executed; index++) {
                requireActive();
                if (remainingNanos() <= 0) {
                    return finish(CodeActStatus.TIMED_OUT, "CodeAct timeout reached before step execution.");
                }
                CodeActStep step = program.steps().get(index);
                eventLog.emit(
                        CodeActEventType.STEP_STARTED,
                        index,
                        step,
                        values("command", StateValue.string(step.command())));

                ShellResult shellResult;
                try {
                    shellResult = shell.runAsync(step.command(), remainingDuration(), cancellation)
                            .toCompletableFuture()
                            .join();
                } catch (CompletionException failure) {
                    Throwable cause = RunHandles.unwrap(failure);
                    if (cause instanceof RunCancelledException cancelled) {
                        throw cancelled;
                    }
                    if (cause instanceof ShellCommandRejectedException rejected) {
                        eventLog.emit(
                                CodeActEventType.POLICY_REJECTED,
                                index,
                                step,
                                values("reason", StateValue.string(rejected.getMessage())));
                        return finish(CodeActStatus.POLICY_DENIED, rejected.getMessage());
                    }
                    return finish(
                            CodeActStatus.FAILED,
                            "Shell runtime failed at step '" + step.id() + "': " + failureDescription(cause));
                }

                CodeActOutputBudget.BoundedOutput bounded = outputBudget.capture(shellResult);
                CodeActStepResult stepResult = new CodeActStepResult(
                        index,
                        step,
                        bounded.stdout(),
                        bounded.stderr(),
                        shellResult.exitCode(),
                        bounded.truncated(),
                        shellResult.timedOut());
                stepResults.add(stepResult);
                eventLog.emit(
                        CodeActEventType.STEP_COMPLETED,
                        index,
                        step,
                        values(
                                "stdout", StateValue.string(stepResult.stdout()),
                                "stderr", StateValue.string(stepResult.stderr()),
                                "exitCode", StateValue.integer(stepResult.exitCode()),
                                "truncated", StateValue.bool(stepResult.truncated()),
                                "timedOut", StateValue.bool(stepResult.timedOut())));
                emitOutputLimitIfNeeded();

                if (stepResult.timedOut()) {
                    return finish(CodeActStatus.TIMED_OUT, "Step '" + step.id() + "' timed out.");
                }
                if (stepResult.exitCode() != 0) {
                    return finish(
                            CodeActStatus.FAILED,
                            "Step '" + step.id() + "' exited with code " + stepResult.exitCode() + ".");
                }
            }

            if (program.steps().size() > options.maxSteps()) {
                eventLog.emit(
                        CodeActEventType.LIMIT_REACHED,
                        values(
                                "limit", StateValue.string("maxSteps"),
                                "configured", StateValue.integer(options.maxSteps()),
                                "submitted", StateValue.integer(program.steps().size())));
                return finish(
                        CodeActStatus.MAX_STEPS_REACHED,
                        "Program exceeded the configured maximum of " + options.maxSteps() + " steps.");
            }
            return finish(CodeActStatus.COMPLETED, null);
        }

        private CodeActResult preflightPolicy() {
            for (int index = 0; index < program.steps().size(); index++) {
                CodeActStep step = program.steps().get(index);
                ShellDecision decision = workspacePolicy.evaluate(
                        new ShellRequest(step.command(), options.workspaceRoot().toString()));
                if (!decision.allowed()) {
                    eventLog.emit(
                            CodeActEventType.POLICY_REJECTED,
                            index,
                            step,
                            values("reason", StateValue.string(decision.reason())));
                    return finish(CodeActStatus.POLICY_DENIED, decision.reason());
                }
            }
            return null;
        }

        private ToolApprovalRequest approvalRequest() {
            String callId = runId + ":execute-code";
            ToolInvocationContext context = new ToolInvocationContext(
                    runId,
                    callId,
                    new InvocationId(callId),
                    cancellation,
                    executor,
                    Map.of("programDigest", StateValue.string(programDigest)));
            return ToolApprovals.request(context, approvalTool, approvalArguments(program));
        }

        private ToolApprovalDecision awaitApproval(CompletionStage<ToolApprovalDecision> stage) {
            Objects.requireNonNull(stage, "approvalHandler returned null");
            requireActive();
            long remaining = remainingNanos();
            if (remaining <= 0) {
                throw new ApprovalTimedOutException();
            }
            CompletableFuture<ToolApprovalDecision> decision = stage.toCompletableFuture();
            CompletableFuture<Void> cancelled = cancellation.cancelledAsync().toCompletableFuture();
            try {
                CompletableFuture.anyOf(decision, cancelled).get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                decision.cancel(true);
                throw new ApprovalTimedOutException();
            } catch (InterruptedException exception) {
                decision.cancel(true);
                cancellation.cancel();
                Thread.currentThread().interrupt();
                throw new RunCancelledException();
            } catch (ExecutionException exception) {
                Throwable cause = RunHandles.unwrap(exception.getCause());
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CodeActExecutionException("Approval handler failed.", cause);
            }
            if (cancellation.isCancellationRequested()) {
                decision.cancel(true);
            }
            requireActive();
            try {
                return Objects.requireNonNull(decision.join(), "approvalHandler returned a null decision");
            } catch (CompletionException failure) {
                Throwable cause = RunHandles.unwrap(failure);
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new CodeActExecutionException("Approval handler failed.", cause);
            }
        }

        private CompletionStage<ToolApprovalDecision> requestApprovalAsync(ToolApprovalRequest request) {
            return CompletableFuture.supplyAsync(
                            () -> options.approvalHandler().requestApprovalAsync(request, cancellation), executor)
                    .thenCompose(stage -> Objects.requireNonNull(stage, "approvalHandler returned null"));
        }

        private void validateDecision(ToolApprovalRequest request, ToolApprovalDecision decision) {
            if (!request.approvalId().equals(decision.approvalId())
                    || !request.invocationId().equals(decision.invocationId())
                    || !request.requestDigest().equals(decision.requestDigest())) {
                throw new CodeActExecutionException(
                        "Approval decision does not match the exact issued CodeAct request.");
            }
        }

        private void emitOutputLimitIfNeeded() {
            if (outputBudget.truncated() && !outputLimitEmitted) {
                outputLimitEmitted = true;
                eventLog.emit(
                        CodeActEventType.LIMIT_REACHED,
                        values(
                                "limit", StateValue.string("maxOutputBytes"),
                                "configured", StateValue.integer(options.maxOutputBytes())));
            }
        }

        private CodeActResult finish(CodeActStatus status, String detail) {
            CodeActEventType terminal = status == CodeActStatus.COMPLETED
                    ? CodeActEventType.RUN_COMPLETED
                    : CodeActEventType.RUN_TERMINATED;
            eventLog.emit(
                    terminal,
                    values(
                            "status", StateValue.string(status.name()),
                            "completedSteps", StateValue.integer(stepResults.size()),
                            "capturedOutputBytes", StateValue.integer(outputBudget.capturedBytes()),
                            "outputTruncated", StateValue.bool(outputBudget.truncated()),
                            "detail", detail == null ? StateValue.nullValue() : StateValue.string(detail)));
            CodeActState state = new CodeActState(
                    runId,
                    programDigest,
                    status,
                    stepResults.size(),
                    stepResults.size(),
                    outputBudget.capturedBytes(),
                    outputBudget.truncated());
            return new CodeActResult(runId, status, state, stepResults, eventLog.snapshot(), detail);
        }

        private void emitCancellation() {
            if (cancellationEmitted) {
                return;
            }
            cancellationEmitted = true;
            eventLog.emit(
                    CodeActEventType.RUN_CANCELLED,
                    values(
                            "status", StateValue.string(CodeActStatus.CANCELLED.name()),
                            "completedSteps", StateValue.integer(stepResults.size()),
                            "capturedOutputBytes", StateValue.integer(outputBudget.capturedBytes()),
                            "outputTruncated", StateValue.bool(outputBudget.truncated())));
        }

        private void requireActive() {
            if (cancellation.isCancellationRequested()) {
                throw new RunCancelledException();
            }
        }

        private long remainingNanos() {
            return timeoutNanos - (System.nanoTime() - startedNanos);
        }

        private Duration remainingDuration() {
            return Duration.ofNanos(Math.max(1, remainingNanos()));
        }
    }

    private static LocalShellExecutorOptions shellOptions(
            CodeActOptions options, WorkspaceCommandPolicy workspacePolicy) {
        Path workspace = options.workspaceRoot();
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", workspace.toString());
        environment.put("USERPROFILE", workspace.toString());
        environment.put("PWD", workspace.toString());
        environment.put("TMPDIR", workspace.toString());
        environment.put("TEMP", workspace.toString());
        environment.put("TMP", workspace.toString());
        return LocalShellExecutorOptions.builder()
                .mode(ShellMode.STATELESS)
                .workingDirectory(workspace.toString())
                .confineWorkingDirectory(true)
                .environment(environment)
                .cleanEnvironment(true)
                .policy(workspacePolicy.combinedPolicy())
                .timeout(options.timeout())
                .maxOutputBytes(options.maxOutputBytes())
                .build();
    }

    private static FunctionTool approvalTool() {
        ToolMetadata metadata = new ToolMetadata(
                EXECUTE_CODE_TOOL_NAME,
                "Execute an explicitly approved bounded sequence of shell-backed CodeAct steps.",
                Set.of(ToolCapability.FUNCTION, ToolCapability.SHELL),
                ToolApprovalMode.ALWAYS_REQUIRE,
                approvalInputSchema(),
                StateValue.object(Map.of("type", StateValue.string("string"))));
        return FunctionTool.create(
                metadata,
                (context, arguments) -> CompletableFuture.failedFuture(new UnsupportedOperationException(
                        "The approval-only CodeAct tool is not directly invokable.")));
    }

    private static StateValue.ObjectValue approvalInputSchema() {
        StateValue.ObjectValue stringSchema = StateValue.object(Map.of("type", StateValue.string("string")));
        StateValue.ObjectValue integerSchema = StateValue.object(Map.of("type", StateValue.string("integer")));
        LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>();
        properties.put("workspace", stringSchema);
        properties.put(
                "steps",
                StateValue.object(Map.of(
                        "type", StateValue.string("array"),
                        "items",
                                StateValue.object(Map.of(
                                        "type", StateValue.string("object"),
                                        "properties",
                                                StateValue.object(Map.of(
                                                        "id", stringSchema,
                                                        "command", stringSchema)),
                                        "required",
                                                StateValue.array(
                                                        List.of(StateValue.string("id"), StateValue.string("command"))),
                                        "additionalProperties", StateValue.bool(false))))));
        properties.put("maxSteps", integerSchema);
        properties.put("timeoutMillis", integerSchema);
        properties.put("maxOutputBytes", integerSchema);
        LinkedHashMap<String, StateValue> schema = new LinkedHashMap<>();
        schema.put("type", StateValue.string("object"));
        schema.put("properties", StateValue.object(properties));
        schema.put(
                "required",
                StateValue.array(List.of(
                        StateValue.string("workspace"),
                        StateValue.string("steps"),
                        StateValue.string("maxSteps"),
                        StateValue.string("timeoutMillis"),
                        StateValue.string("maxOutputBytes"))));
        schema.put("additionalProperties", StateValue.bool(false));
        return StateValue.object(schema);
    }

    private StateValue.ObjectValue approvalArguments(CodeActProgram program) {
        List<StateValue> steps = program.steps().stream()
                .map(step -> StateValue.object(values(
                        "id", StateValue.string(step.id()),
                        "command", StateValue.string(step.command()))))
                .map(StateValue.class::cast)
                .toList();
        return StateValue.object(values(
                "workspace", StateValue.string(options.workspaceRoot().toString()),
                "steps", StateValue.array(steps),
                "maxSteps", StateValue.integer(options.maxSteps()),
                "timeoutMillis", StateValue.integer(options.timeout().toMillis()),
                "maxOutputBytes", StateValue.integer(options.maxOutputBytes())));
    }

    private static LinkedHashMap<String, StateValue> values(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("entries must contain key-value pairs.");
        }
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            String name = CodeActValidation.requireNonBlank((String) entries[index], "entry name");
            StateValue value = Objects.requireNonNull((StateValue) entries[index + 1], "entry value");
            values.put(name, value);
        }
        return values;
    }

    private static String failureDescription(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class ApprovalTimedOutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
