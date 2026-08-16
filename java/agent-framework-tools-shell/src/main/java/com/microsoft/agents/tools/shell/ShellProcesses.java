// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellProcesses {
    private static final Set<String> PRESERVED_ENVIRONMENT =
            Set.of("PATH", "HOME", "USER", "USERNAME", "USERPROFILE", "SystemRoot", "TEMP", "TMP");

    private ShellProcesses() {}

    static ShellResult run(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Set<String> removedEnvironmentVariables,
            boolean cleanEnvironment,
            Duration timeout,
            int maxOutputBytes,
            RunCancellation cancellation,
            ExecutorService executor) {
        if (cancellation.isCancellationRequested()) {
            throw new RunCancelledException();
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        configureEnvironment(builder.environment(), environment, removedEnvironmentVariables, cleanEnvironment);

        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new ShellExecutionException(
                    "Failed to launch shell process '" + command.getFirst() + "'.", exception);
        }

        HeadTailBuffer stdout = new HeadTailBuffer(maxOutputBytes);
        HeadTailBuffer stderr = new HeadTailBuffer(maxOutputBytes);
        CompletableFuture<Void> stdoutReader =
                CompletableFuture.runAsync(() -> drain(process.getInputStream(), stdout), executor);
        CompletableFuture<Void> stderrReader =
                CompletableFuture.runAsync(() -> drain(process.getErrorStream(), stderr), executor);
        AtomicBoolean cancelled = new AtomicBoolean();
        RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
            cancelled.set(true);
            killTree(process);
        });

        boolean timedOut = false;
        try {
            boolean completed = timeout == null
                    ? waitWithoutTimeout(process)
                    : process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!completed) {
                timedOut = true;
                killTree(process);
                waitWithoutTimeout(process);
            }
            stdoutReader.join();
            stderrReader.join();
            if (cancelled.get() || cancellation.isCancellationRequested()) {
                throw new RunCancelledException();
            }
            HeadTailBuffer.CapturedOutput capturedStdout = stdout.capture();
            HeadTailBuffer.CapturedOutput capturedStderr = stderr.capture();
            int exitCode = timedOut ? 124 : process.exitValue();
            return new ShellResult(
                    capturedStdout.text(),
                    capturedStderr.text(),
                    exitCode,
                    Duration.ofNanos(System.nanoTime() - started),
                    capturedStdout.truncated() || capturedStderr.truncated(),
                    timedOut);
        } catch (InterruptedException exception) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new RunCancelledException("Shell command was interrupted.", exception);
        } finally {
            registration.close();
        }
    }

    static void configureEnvironment(
            Map<String, String> target, Map<String, String> additions, Set<String> removals, boolean cleanEnvironment) {
        if (cleanEnvironment) {
            Map<String, String> inherited = Map.copyOf(target);
            target.clear();
            PRESERVED_ENVIRONMENT.forEach(name -> {
                String value = inherited.get(name);
                if (value != null) {
                    target.put(name, value);
                }
            });
        }
        removals.forEach(target::remove);
        target.putAll(additions);
    }

    static void killTree(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static boolean waitWithoutTimeout(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private static void drain(InputStream stream, HeadTailBuffer buffer) {
        byte[] chunk = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                if (read > 0) {
                    buffer.append(chunk, read);
                }
            }
        } catch (IOException exception) {
            throw new ShellExecutionException("Failed while reading shell output.", exception);
        }
    }
}
