// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.tools.shell.ShellPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Configures bounded approval-gated CodeAct execution. */
public final class CodeActOptions {
    /** Default maximum number of shell-backed steps. */
    public static final int DEFAULT_MAX_STEPS = 8;

    /** Default wall-clock bound for approval and execution. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Default aggregate retained UTF-8 output bound. */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    private final Path workspaceRoot;
    private final ShellPolicy shellPolicy;
    private final CodeActApprovalHandler approvalHandler;
    private final int maxSteps;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final List<CodeActEventListener> eventListeners;

    private CodeActOptions(Builder builder) {
        workspaceRoot = resolveWorkspace(builder.workspaceRoot);
        shellPolicy = Objects.requireNonNull(builder.shellPolicy, "shellPolicy must be explicitly configured.");
        approvalHandler =
                Objects.requireNonNull(builder.approvalHandler, "approvalHandler must be explicitly configured.");
        if (builder.maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be greater than zero.");
        }
        maxSteps = builder.maxSteps;
        timeout = Objects.requireNonNull(builder.timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        try {
            timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("timeout is too large.", exception);
        }
        if (builder.maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be greater than zero.");
        }
        maxOutputBytes = builder.maxOutputBytes;
        eventListeners = List.copyOf(builder.eventListeners);
    }

    /**
     * Creates an options builder for an existing workspace directory.
     *
     * @param workspaceRoot workspace directory anchored for every shell-backed step
     * @return options builder
     */
    public static Builder builder(Path workspaceRoot) {
        return new Builder(workspaceRoot);
    }

    /**
     * Returns the canonical existing workspace root.
     *
     * @return real absolute workspace path
     */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Returns the explicitly configured caller policy.
     *
     * @return shell policy layered after mandatory workspace checks
     */
    public ShellPolicy shellPolicy() {
        return shellPolicy;
    }

    /**
     * Returns the bundled approval handler.
     *
     * @return explicit approval handler
     */
    public CodeActApprovalHandler approvalHandler() {
        return approvalHandler;
    }

    /**
     * Returns the positive step bound.
     *
     * @return maximum executed steps
     */
    public int maxSteps() {
        return maxSteps;
    }

    /**
     * Returns the wall-clock bound covering approval and execution.
     *
     * @return positive timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the aggregate retained UTF-8 output bound.
     *
     * @return positive output byte bound
     */
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /**
     * Returns optional event listeners in registration order.
     *
     * @return immutable listeners
     */
    public List<CodeActEventListener> eventListeners() {
        return eventListeners;
    }

    /** Builds immutable {@link CodeActOptions}. */
    public static final class Builder {
        private final Path workspaceRoot;
        private ShellPolicy shellPolicy;
        private CodeActApprovalHandler approvalHandler;
        private int maxSteps = DEFAULT_MAX_STEPS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        private final ArrayList<CodeActEventListener> eventListeners = new ArrayList<>();

        private Builder(Path workspaceRoot) {
            this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }

        /**
         * Sets the required caller command policy.
         *
         * <p>Mandatory workspace checks run before this policy and cannot be disabled.
         *
         * @param shellPolicy explicit command policy
         * @return this builder
         */
        public Builder shellPolicy(ShellPolicy shellPolicy) {
            this.shellPolicy = Objects.requireNonNull(shellPolicy, "shellPolicy");
            return this;
        }

        /**
         * Sets the required bundled approval handler.
         *
         * @param approvalHandler approval handler
         * @return this builder
         */
        public Builder approvalHandler(CodeActApprovalHandler approvalHandler) {
            this.approvalHandler = Objects.requireNonNull(approvalHandler, "approvalHandler");
            return this;
        }

        /**
         * Sets the positive maximum executed step count.
         *
         * @param maxSteps step bound
         * @return this builder
         */
        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * Sets the positive wall-clock bound covering approval and execution.
         *
         * @param timeout run timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the positive aggregate retained UTF-8 output bound.
         *
         * @param maxOutputBytes output byte bound
         * @return this builder
         */
        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        /**
         * Adds an optional event observer.
         *
         * @param eventListener event observer
         * @return this builder
         */
        public Builder eventListener(CodeActEventListener eventListener) {
            eventListeners.add(Objects.requireNonNull(eventListener, "eventListener"));
            return this;
        }

        /**
         * Creates immutable validated options.
         *
         * @return CodeAct options
         */
        public CodeActOptions build() {
            return new CodeActOptions(this);
        }
    }

    private static Path resolveWorkspace(Path workspaceRoot) {
        try {
            Path real = workspaceRoot.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("workspaceRoot must identify an existing directory.");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspaceRoot must identify an existing directory.", exception);
        }
    }
}
