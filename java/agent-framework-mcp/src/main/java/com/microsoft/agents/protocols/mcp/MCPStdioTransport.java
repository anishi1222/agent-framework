// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.ValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configures a local MCP child process without invoking a shell.
 *
 * <p>The child receives only explicitly configured environment values plus inherited variables named
 * by {@link #inheritedEnvironmentAllowlist()}. A working directory is accepted only when it is below
 * one of {@link #allowedWorkingDirectories()}.
 */
public final class MCPStdioTransport implements MCPTransport {
    private final String command;

    private final List<String> arguments;

    private final Map<String, String> environment;

    private final Set<String> inheritedEnvironmentAllowlist;

    private final Path workingDirectory;

    private final Set<Path> allowedWorkingDirectories;

    private final Duration shutdownTimeout;

    private MCPStdioTransport(Builder builder) {
        command = validateCommand(builder.command);
        arguments = List.copyOf(builder.arguments);
        environment = copyEnvironment(builder.environment);
        inheritedEnvironmentAllowlist = copyEnvironmentNames(builder.inheritedEnvironmentAllowlist);
        workingDirectory = validateWorkingDirectory(builder.workingDirectory, builder.allowedWorkingDirectories);
        allowedWorkingDirectories = builder.allowedWorkingDirectories.stream()
                .map(MCPStdioTransport::realPath)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        shutdownTimeout = MCPValidation.positive(builder.shutdownTimeout, "shutdownTimeout");
    }

    /**
     * Creates a builder for an executable path or command name.
     *
     * @param command executable path or command name; shell fragments are rejected
     * @return transport builder
     */
    public static Builder builder(String command) {
        return new Builder(command);
    }

    /**
     * Returns the executable without shell interpretation.
     *
     * @return executable path or command
     */
    public String command() {
        return command;
    }

    /**
     * Returns the immutable argument vector.
     *
     * @return arguments
     */
    public List<String> arguments() {
        return arguments;
    }

    /**
     * Returns explicitly configured child environment values.
     *
     * @return immutable environment
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Returns names of parent environment variables allowed into the child.
     *
     * @return immutable allowlist
     */
    public Set<String> inheritedEnvironmentAllowlist() {
        return inheritedEnvironmentAllowlist;
    }

    /**
     * Returns the validated working directory, or {@code null}.
     *
     * @return working directory
     */
    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * Returns normalized roots under which a working directory is allowed.
     *
     * @return immutable allowed roots
     */
    public Set<Path> allowedWorkingDirectories() {
        return allowedWorkingDirectories;
    }

    /**
     * Returns the child shutdown deadline.
     *
     * @return positive timeout
     */
    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    @Override
    public String toString() {
        return "MCPStdioTransport[command="
                + command
                + ", argumentCount="
                + arguments.size()
                + ", environmentKeys="
                + environment.keySet()
                + ", inheritedEnvironmentAllowlist="
                + inheritedEnvironmentAllowlist
                + ", workingDirectory="
                + workingDirectory
                + "]";
    }

    /** Builds a secure stdio transport configuration. */
    public static final class Builder {
        private static final Set<String> DEFAULT_ENVIRONMENT =
                Set.of("HOME", "LANG", "LC_ALL", "PATH", "SYSTEMROOT", "TEMP", "TMP", "TMPDIR", "USERPROFILE");

        private final String command;

        private final List<String> arguments = new ArrayList<>();

        private final Map<String, String> environment = new LinkedHashMap<>();

        private final Set<String> inheritedEnvironmentAllowlist = new LinkedHashSet<>(DEFAULT_ENVIRONMENT);

        private final Set<Path> allowedWorkingDirectories = new LinkedHashSet<>();

        private Path workingDirectory;

        private Duration shutdownTimeout = Duration.ofSeconds(5);

        private Builder(String command) {
            this.command = command;
        }

        /**
         * Replaces the child argument vector.
         *
         * @param arguments arguments passed directly to {@link ProcessBuilder}
         * @return this builder
         */
        public Builder arguments(List<String> arguments) {
            Objects.requireNonNull(arguments, "arguments");
            this.arguments.clear();
            arguments.forEach(argument -> this.arguments.add(Objects.requireNonNull(argument, "argument")));
            return this;
        }

        /**
         * Appends one child argument.
         *
         * @param argument literal argument
         * @return this builder
         */
        public Builder argument(String argument) {
            arguments.add(Objects.requireNonNull(argument, "argument"));
            return this;
        }

        /**
         * Replaces explicit environment values.
         *
         * @param environment child environment values
         * @return this builder
         */
        public Builder environment(Map<String, String> environment) {
            this.environment.clear();
            this.environment.putAll(copyEnvironment(environment));
            return this;
        }

        /**
         * Replaces the parent-environment allowlist.
         *
         * @param names allowed environment variable names
         * @return this builder
         */
        public Builder inheritedEnvironmentAllowlist(Set<String> names) {
            inheritedEnvironmentAllowlist.clear();
            inheritedEnvironmentAllowlist.addAll(copyEnvironmentNames(names));
            return this;
        }

        /**
         * Sets the child working directory.
         *
         * @param directory existing directory
         * @return this builder
         */
        public Builder workingDirectory(Path directory) {
            workingDirectory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /**
         * Replaces roots under which the working directory is permitted.
         *
         * @param directories allowed roots
         * @return this builder
         */
        public Builder allowedWorkingDirectories(Set<Path> directories) {
            Objects.requireNonNull(directories, "directories");
            allowedWorkingDirectories.clear();
            directories.forEach(path -> allowedWorkingDirectories.add(Objects.requireNonNull(path, "directory")));
            return this;
        }

        /**
         * Sets the graceful child shutdown deadline.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder shutdownTimeout(Duration timeout) {
            shutdownTimeout = timeout;
            return this;
        }

        /**
         * Creates the immutable configuration.
         *
         * @return validated stdio transport
         */
        public MCPStdioTransport build() {
            return new MCPStdioTransport(this);
        }
    }

    private static String validateCommand(String command) {
        String value = MCPValidation.nonBlank(command, "command");
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new ValidationException("command must be one executable name without control characters.");
        }
        return value;
    }

    private static Map<String, String> copyEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            String safeKey = validateEnvironmentName(key);
            String safeValue = Objects.requireNonNull(value, "environment value");
            if (safeValue.indexOf('\0') >= 0) {
                throw new ValidationException("environment value for '" + safeKey + "' contains NUL.");
            }
            copy.put(safeKey, safeValue);
        });
        return Map.copyOf(copy);
    }

    private static Set<String> copyEnvironmentNames(Set<String> names) {
        Objects.requireNonNull(names, "names");
        return names.stream()
                .map(MCPStdioTransport::validateEnvironmentName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String validateEnvironmentName(String name) {
        String value = MCPValidation.nonBlank(name, "environment name");
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ValidationException("environment name '" + value + "' is invalid.");
        }
        return value;
    }

    private static Path validateWorkingDirectory(Path directory, Set<Path> allowedRoots) {
        if (directory == null) {
            return null;
        }
        if (allowedRoots.isEmpty()) {
            throw new ValidationException("allowedWorkingDirectories must be configured when workingDirectory is set.");
        }
        Path resolved = realPath(directory);
        if (!Files.isDirectory(resolved)) {
            throw new ValidationException("workingDirectory must be an existing directory.");
        }
        boolean allowed = allowedRoots.stream().map(MCPStdioTransport::realPath).anyMatch(resolved::startsWith);
        if (!allowed) {
            throw new ValidationException("workingDirectory is outside allowedWorkingDirectories.");
        }
        return resolved;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException exception) {
            throw new ValidationException("working-directory policy paths must exist and be resolvable.", exception);
        }
    }
}
