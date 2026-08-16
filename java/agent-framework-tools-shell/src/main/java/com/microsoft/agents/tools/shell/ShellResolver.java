// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class ShellResolver {
    static final String ENVIRONMENT_VARIABLE = "AGENT_FRAMEWORK_SHELL";

    private ShellResolver() {}

    static ResolvedShell resolve(List<String> override) {
        if (override != null) {
            return explicit(override);
        }
        String environmentOverride = System.getenv(ENVIRONMENT_VARIABLE);
        if (environmentOverride != null && !environmentOverride.isBlank()) {
            return explicit(List.of(environmentOverride));
        }
        if (isWindows()) {
            String pwsh = findOnPath("pwsh.exe");
            if (pwsh != null) {
                return explicit(List.of(pwsh));
            }
            String powershell = findOnPath("powershell.exe");
            if (powershell != null) {
                return explicit(List.of(powershell));
            }
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            return explicit(List.of(Path.of(systemRoot, "System32", "cmd.exe").toString()));
        }
        if (Files.isExecutable(Path.of("/bin/bash"))) {
            return explicit(List.of("/bin/bash"));
        }
        return explicit(List.of("/bin/sh"));
    }

    static ResolvedShell commandPrefix(List<String> prefix, ShellKind kind) {
        return new ResolvedShell(prefix, kind, false);
    }

    private static ResolvedShell explicit(List<String> command) {
        return new ResolvedShell(command, classify(command.getFirst()), true);
    }

    private static ShellKind classify(String binary) {
        String fileName = Path.of(binary).getFileName().toString().toLowerCase(Locale.ROOT);
        int extension = fileName.lastIndexOf('.');
        String base = extension > 0 ? fileName.substring(0, extension) : fileName;
        return switch (base) {
            case "pwsh", "powershell" -> ShellKind.POWERSHELL;
            case "cmd" -> ShellKind.CMD;
            case "bash" -> ShellKind.BASH;
            default -> ShellKind.POSIX;
        };
    }

    private static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, executable);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
