// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Configures host-local shell execution. */
public final class LocalShellExecutorOptions {
    private final ShellMode mode;
    private final List<String> shellCommand;
    private final String workingDirectory;
    private final boolean confineWorkingDirectory;
    private final Map<String, String> environment;
    private final Set<String> removedEnvironmentVariables;
    private final boolean cleanEnvironment;
    private final ShellPolicy policy;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final boolean acknowledgeUnsafe;
    private final Consumer<String> commandObserver;

    private LocalShellExecutorOptions(Builder builder) {
        mode = Objects.requireNonNull(builder.mode, "mode");
        shellCommand = builder.shellCommand == null ? null : List.copyOf(builder.shellCommand);
        if (shellCommand != null) {
            if (shellCommand.isEmpty() || shellCommand.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("shellCommand must contain non-blank arguments.");
            }
        }
        workingDirectory = optionalNonBlank(builder.workingDirectory, "workingDirectory");
        confineWorkingDirectory = builder.confineWorkingDirectory;
        environment = Map.copyOf(builder.environment);
        removedEnvironmentVariables = Set.copyOf(builder.removedEnvironmentVariables);
        cleanEnvironment = builder.cleanEnvironment;
        policy = Objects.requireNonNull(builder.policy, "policy");
        timeout = builder.timeout;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive when present.");
        }
        maxOutputBytes = builder.maxOutputBytes;
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive.");
        }
        acknowledgeUnsafe = builder.acknowledgeUnsafe;
        commandObserver = builder.commandObserver;
    }

    /**
     * Returns a new options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns default options.
     *
     * @return persistent, approval-gated defaults with a 30-second timeout
     */
    public static LocalShellExecutorOptions defaults() {
        return builder().build();
    }

    /** Returns the execution mode. */
    public ShellMode mode() {
        return mode;
    }

    /** Returns the explicit shell command prefix, or {@code null} for platform resolution. */
    public List<String> shellCommand() {
        return shellCommand;
    }

    /** Returns the configured working directory, or {@code null} for the process directory. */
    public String workingDirectory() {
        return workingDirectory;
    }

    /** Reports whether persistent commands are re-anchored to the configured directory. */
    public boolean confineWorkingDirectory() {
        return confineWorkingDirectory;
    }

    /** Returns environment additions and replacements. */
    public Map<String, String> environment() {
        return environment;
    }

    /** Returns inherited environment variables removed from the child. */
    public Set<String> removedEnvironmentVariables() {
        return removedEnvironmentVariables;
    }

    /** Reports whether only a minimal inherited environment is retained. */
    public boolean cleanEnvironment() {
        return cleanEnvironment;
    }

    /** Returns the pre-execution policy. */
    public ShellPolicy policy() {
        return policy;
    }

    /** Returns the default command timeout, or {@code null} when disabled. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the per-stream UTF-8 output bound. */
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /** Reports whether unapproved host execution was explicitly acknowledged. */
    public boolean acknowledgeUnsafe() {
        return acknowledgeUnsafe;
    }

    /** Returns the optional accepted-command observer. */
    public Consumer<String> commandObserver() {
        return commandObserver;
    }

    /** Builds immutable local-shell options. */
    public static final class Builder {
        private ShellMode mode = ShellMode.PERSISTENT;
        private List<String> shellCommand;
        private String workingDirectory;
        private boolean confineWorkingDirectory = true;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private final Set<String> removedEnvironmentVariables = new LinkedHashSet<>();
        private boolean cleanEnvironment;
        private ShellPolicy policy = new ShellPolicy();
        private Duration timeout = Duration.ofSeconds(30);
        private int maxOutputBytes = 64 * 1024;
        private boolean acknowledgeUnsafe;
        private Consumer<String> commandObserver;

        private Builder() {}

        /**
         * Sets the execution mode.
         *
         * @param mode execution mode
         * @return this builder
         */
        public Builder mode(ShellMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Sets an explicit shell binary and launch-time argument prefix.
         *
         * @param shellCommand shell command prefix
         * @return this builder
         */
        public Builder shellCommand(List<String> shellCommand) {
            this.shellCommand = Objects.requireNonNull(shellCommand, "shellCommand");
            return this;
        }

        /**
         * Sets the process working directory.
         *
         * @param workingDirectory working directory
         * @return this builder
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * Controls persistent-command re-anchoring.
         *
         * @param confineWorkingDirectory whether to re-anchor every command
         * @return this builder
         */
        public Builder confineWorkingDirectory(boolean confineWorkingDirectory) {
            this.confineWorkingDirectory = confineWorkingDirectory;
            return this;
        }

        /**
         * Adds or replaces child environment variables.
         *
         * @param environment environment values
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) {
            this.environment.clear();
            Objects.requireNonNull(environment, "environment")
                    .forEach((key, value) -> this.environment.put(
                            requireNonBlank(key, "environment key"),
                            Objects.requireNonNull(value, "environment value")));
            return this;
        }

        /**
         * Removes inherited child environment variables.
         *
         * @param names variable names
         * @return this builder
         */
        public Builder removedEnvironmentVariables(Set<String> names) {
            removedEnvironmentVariables.clear();
            Objects.requireNonNull(names, "names")
                    .forEach(name -> removedEnvironmentVariables.add(requireNonBlank(name, "environment name")));
            return this;
        }

        /**
         * Controls parent-environment inheritance.
         *
         * @param cleanEnvironment whether to retain only essential inherited variables
         * @return this builder
         */
        public Builder cleanEnvironment(boolean cleanEnvironment) {
            this.cleanEnvironment = cleanEnvironment;
            return this;
        }

        /**
         * Sets the command policy.
         *
         * @param policy command policy
         * @return this builder
         */
        public Builder policy(ShellPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Sets the default command timeout.
         *
         * @param timeout positive timeout, or {@code null} to disable
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the per-stream output bound.
         *
         * @param maxOutputBytes positive byte bound
         * @return this builder
         */
        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        /**
         * Acknowledges the risk of disabling approval for host execution.
         *
         * @param acknowledgeUnsafe acknowledgement value
         * @return this builder
         */
        public Builder acknowledgeUnsafe(boolean acknowledgeUnsafe) {
            this.acknowledgeUnsafe = acknowledgeUnsafe;
            return this;
        }

        /**
         * Sets an observer invoked after policy acceptance and before execution.
         *
         * @param commandObserver command observer
         * @return this builder
         */
        public Builder commandObserver(Consumer<String> commandObserver) {
            this.commandObserver = commandObserver;
            return this;
        }

        /**
         * Builds immutable options.
         *
         * @return local shell options
         */
        public LocalShellExecutorOptions build() {
            return new LocalShellExecutorOptions(this);
        }
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
