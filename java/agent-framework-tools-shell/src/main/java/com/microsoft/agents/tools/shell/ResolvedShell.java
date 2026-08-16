// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

record ResolvedShell(List<String> prefix, ShellKind kind, boolean appendPersistentArguments) {
    ResolvedShell {
        prefix = List.copyOf(prefix);
    }

    String binary() {
        return prefix.getFirst();
    }

    List<String> statelessCommand(String command) {
        ArrayList<String> result = new ArrayList<>(prefix);
        String effectiveCommand = kind == ShellKind.POWERSHELL
                ? "$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); " + command
                : command;
        switch (kind) {
            case POWERSHELL -> {
                result.addAll(List.of("-NoProfile", "-NoLogo", "-NonInteractive", "-Command", effectiveCommand));
            }
            case CMD -> result.addAll(List.of("/d", "/c", effectiveCommand));
            case BASH -> result.addAll(List.of("--noprofile", "--norc", "-c", effectiveCommand));
            case POSIX -> result.addAll(List.of("-c", effectiveCommand));
        }
        return List.copyOf(result);
    }

    List<String> persistentCommand() {
        if (!appendPersistentArguments) {
            return prefix;
        }
        ArrayList<String> result = new ArrayList<>(prefix);
        switch (kind) {
            case POWERSHELL -> result.addAll(List.of("-NoProfile", "-NoLogo", "-NonInteractive", "-Command", "-"));
            case CMD -> throw new IllegalArgumentException("Persistent mode is not supported for cmd.exe.");
            case BASH -> result.addAll(List.of("--noprofile", "--norc"));
            case POSIX -> {
                // Generic POSIX shells read commands from stdin without extra arguments.
            }
        }
        return List.copyOf(result);
    }

    String persistentScript(String command, String sentinel) {
        if (kind == ShellKind.POWERSHELL) {
            String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
            return "& { $__af_rc = 0; try { $__af_cmd = [System.Text.Encoding]::UTF8.GetString("
                    + "[Convert]::FromBase64String('"
                    + encoded
                    + "')); Invoke-Expression $__af_cmd; "
                    + "if ($LASTEXITCODE -ne $null) { $__af_rc = $LASTEXITCODE } "
                    + "elseif (-not $?) { $__af_rc = 1 } } catch { "
                    + "[Console]::Error.WriteLine($_.ToString()); $__af_rc = 1 } finally { "
                    + "[Console]::WriteLine('"
                    + sentinel
                    + "_' + $__af_rc); [Console]::Out.Flush() } }\n";
        }
        return "__af_e=$-; set +e; { "
                + command
                + "\n}; __af_rc=$?; case \"$__af_e\" in *e*) set -e;; esac; printf '\\n"
                + sentinel
                + "_%s\\n' \"$__af_rc\"\n";
    }
}
