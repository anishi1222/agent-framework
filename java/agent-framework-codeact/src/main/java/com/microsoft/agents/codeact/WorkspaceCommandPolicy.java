// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.tools.shell.ShellDecision;
import com.microsoft.agents.tools.shell.ShellPolicy;
import com.microsoft.agents.tools.shell.ShellRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class WorkspaceCommandPolicy {
    private static final Pattern PARENT_SEGMENT = Pattern.compile("(^|[/\\\\\\s'\"=,(])\\.\\.(?:[/\\\\]|$)");

    private static final Pattern POSIX_ABSOLUTE_PATH = Pattern.compile("(^|[\\s'\"=,(])/+");

    private static final Pattern WINDOWS_ABSOLUTE_PATH =
            Pattern.compile("(?i)(^|[\\s'\"=,(])(?:[a-z]:[/\\\\]|\\\\\\\\)");

    private static final Pattern HOME_PATH = Pattern.compile("(^|[\\s'\"=,(])~(?:[/\\\\]|$)");

    private static final Pattern WINDOWS_VARIABLE = Pattern.compile("%[A-Za-z_][A-Za-z0-9_]*%");

    private static final Pattern DIRECTORY_CHANGE =
            Pattern.compile("(?i)(^|[\\s;&|'\"(])(cd|chdir|pushd|popd)(?:\\s|$)");

    private final Path workspaceRoot;
    private final ShellPolicy callerPolicy;
    private final ShellPolicy combinedPolicy;

    WorkspaceCommandPolicy(Path workspaceRoot, ShellPolicy callerPolicy) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.callerPolicy = Objects.requireNonNull(callerPolicy, "callerPolicy");
        combinedPolicy = new ShellPolicy(List.of(), null, this::evaluate);
    }

    ShellPolicy combinedPolicy() {
        return combinedPolicy;
    }

    ShellDecision evaluate(ShellRequest request) {
        Objects.requireNonNull(request, "request");
        if (!workspaceRoot.toString().equals(request.workingDirectory())) {
            return ShellDecision.deny("working directory is not the configured workspace root");
        }
        String command = request.command();
        if (command.indexOf('\0') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
            return ShellDecision.deny("multi-line and control-character commands are not allowed");
        }
        if (command.indexOf('$') >= 0
                || command.indexOf('`') >= 0
                || WINDOWS_VARIABLE.matcher(command).find()) {
            return ShellDecision.deny("variable and command substitution are not allowed by workspace confinement");
        }
        if (PARENT_SEGMENT.matcher(command).find()) {
            return ShellDecision.deny("parent-directory traversal is outside the workspace");
        }
        if (POSIX_ABSOLUTE_PATH.matcher(command).find()
                || WINDOWS_ABSOLUTE_PATH.matcher(command).find()
                || HOME_PATH.matcher(command).find()) {
            return ShellDecision.deny("absolute and home-relative paths are outside the workspace contract");
        }
        if (DIRECTORY_CHANGE.matcher(command).find()) {
            return ShellDecision.deny("directory-changing commands are not allowed");
        }
        return callerPolicy.evaluate(request);
    }
}
