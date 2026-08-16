// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configures one exact local MCP executable without invoking a command shell.
 *
 * @param executable canonical executable path
 * @param arguments immutable argument list
 * @param workingDirectory canonical working directory
 * @param environment cleared child environment
 * @param tools explicitly allowed MCP tools
 * @param timeout tool-call timeout
 */
public record GitHubCopilotMCPStdioServerConfig(
        Path executable,
        List<String> arguments,
        Path workingDirectory,
        Map<String, String> environment,
        List<String> tools,
        Duration timeout)
        implements GitHubCopilotMCPServerConfig {
    /** Creates and validates a local MCP configuration. */
    public GitHubCopilotMCPStdioServerConfig {
        executable = canonical(Objects.requireNonNull(executable, "executable"), "executable");
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException("executable must be an executable regular file.");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        arguments.forEach(value -> validateValue(value, "argument"));
        workingDirectory = canonical(Objects.requireNonNull(workingDirectory, "workingDirectory"), "workingDirectory");
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException("workingDirectory must be a directory.");
        }
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        environment.forEach((name, value) -> {
            validateValue(name, "environment name");
            validateValue(value, "environment value");
        });
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        tools.forEach(value -> validateValue(value, "tool"));
        timeout = positive(timeout);
    }

    @Override
    public String toString() {
        return "GitHubCopilotMCPStdioServerConfig{executable="
                + executable
                + ", arguments="
                + arguments
                + ", workingDirectory="
                + workingDirectory
                + ", environmentNames="
                + environment.keySet()
                + ", tools="
                + tools
                + ", timeout="
                + timeout
                + '}';
    }

    private static Path canonical(Path value, String name) {
        try {
            return value.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(name + " cannot be resolved.", exception);
        }
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return value;
    }

    private static void validateValue(String value, String name) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not be null or contain NUL.");
        }
    }
}
