// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class PersistentShellSession {
    private static final Duration STDERR_QUIESCENCE = Duration.ofMillis(50);
    private static final Duration CLOSE_GRACE = Duration.ofSeconds(2);

    private final ResolvedShell shell;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final Set<String> removedEnvironmentVariables;
    private final boolean cleanEnvironment;
    private final int maxOutputBytes;
    private final ExecutorService executor;
    private final Object lifecycleLock = new Object();
    private final Object outputMonitor = new Object();
    private final ReentrantLock commandLock = new ReentrantLock();
    private final String sentinelTag;

    private Process process;
    private OutputStream stdin;
    private CompletableFuture<Void> stdoutReader;
    private CompletableFuture<Void> stderrReader;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private HeadTailBuffer stderr;
    private boolean stdoutClosed;
    private boolean overflow;

    PersistentShellSession(
            ResolvedShell shell,
            Path workingDirectory,
            Map<String, String> environment,
            Set<String> removedEnvironmentVariables,
            boolean cleanEnvironment,
            int maxOutputBytes,
            ExecutorService executor) {
        this.shell = shell;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.removedEnvironmentVariables = removedEnvironmentVariables;
        this.cleanEnvironment = cleanEnvironment;
        this.maxOutputBytes = maxOutputBytes;
        this.executor = executor;
        byte[] random = new byte[8];
        SecureRandomHolder.INSTANCE.nextBytes(random);
        sentinelTag = HexFormat.of().formatHex(random);
        stderr = new HeadTailBuffer(maxOutputBytes);
    }

    void start() {
        synchronized (lifecycleLock) {
            if (process != null && process.isAlive()) {
                return;
            }
            ProcessBuilder builder = new ProcessBuilder(shell.persistentCommand());
            builder.directory(workingDirectory.toFile());
            ShellProcesses.configureEnvironment(
                    builder.environment(), environment, removedEnvironmentVariables, cleanEnvironment);
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new ShellExecutionException(
                        "Failed to launch persistent shell '" + shell.binary() + "'.", exception);
            }
            stdin = process.getOutputStream();
            resetOutput();
            stdoutReader = CompletableFuture.runAsync(() -> readStdout(process.getInputStream()), executor);
            stderrReader = CompletableFuture.runAsync(() -> readStderr(process.getErrorStream()), executor);
            if (shell.kind() == ShellKind.POWERSHELL) {
                writeRaw("$OutputEncoding = [Console]::OutputEncoding = "
                        + "[System.Text.UTF8Encoding]::new($false);$ErrorActionPreference = 'Stop'\n");
            }
        }
    }

    ShellResult run(String command, Duration timeout, RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            throw new RunCancelledException();
        }
        commandLock.lock();
        try {
            start();
            resetOutput();
            String sentinel = "__AF_END_" + sentinelTag + "_"
                    + java.util.UUID.randomUUID().toString().replace("-", "") + "__";
            byte[] needle = sentinel.getBytes(StandardCharsets.UTF_8);
            AtomicBoolean cancelled = new AtomicBoolean();
            RunCancellationRegistration registration = RunCancellations.register(cancellation, () -> {
                cancelled.set(true);
                closeProcess();
                synchronized (outputMonitor) {
                    outputMonitor.notifyAll();
                }
            });
            long started = System.nanoTime();
            try {
                writeRaw(shell.persistentScript(command, sentinel));
                Boundary boundary = awaitBoundary(needle, timeout, cancelled);
                if (cancelled.get() || cancellation.isCancellationRequested()) {
                    throw new RunCancelledException();
                }
                if (boundary.timedOut()) {
                    Captured captured = capture(-1);
                    closeProcess();
                    return new ShellResult(
                            captured.stdout(),
                            captured.stderr(),
                            124,
                            Duration.ofNanos(System.nanoTime() - started),
                            captured.truncated(),
                            true);
                }
                if (boundary.overflow()) {
                    Captured captured = capture(-1);
                    closeProcess();
                    return new ShellResult(
                            captured.stdout(),
                            captured.stderr(),
                            -1,
                            Duration.ofNanos(System.nanoTime() - started),
                            true,
                            false);
                }
                sleepQuietly(STDERR_QUIESCENCE);
                Captured captured = capture(boundary.sentinelIndex());
                return new ShellResult(
                        captured.stdout(),
                        captured.stderr(),
                        boundary.exitCode(),
                        Duration.ofNanos(System.nanoTime() - started),
                        captured.truncated(),
                        false);
            } finally {
                registration.close();
            }
        } finally {
            commandLock.unlock();
        }
    }

    void close() {
        commandLock.lock();
        try {
            closeProcess();
        } finally {
            commandLock.unlock();
        }
    }

    private Boundary awaitBoundary(byte[] needle, Duration timeout, AtomicBoolean cancelled) {
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        synchronized (outputMonitor) {
            while (true) {
                byte[] current = stdout.toByteArray();
                int sentinelIndex = indexOf(current, needle);
                if (sentinelIndex >= 0) {
                    Integer exitCode = parseExitCode(current, sentinelIndex + needle.length);
                    if (exitCode != null) {
                        return new Boundary(sentinelIndex, exitCode, false, false);
                    }
                }
                if (overflow) {
                    return new Boundary(-1, -1, false, true);
                }
                if (cancelled.get()) {
                    throw new RunCancelledException();
                }
                if (stdoutClosed) {
                    throw new ShellExecutionException("Persistent shell closed before emitting a command boundary.");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new Boundary(-1, 124, true, false);
                }
                try {
                    long waitMillis = timeout == null
                            ? 100
                            : Math.max(1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    outputMonitor.wait(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    closeProcess();
                    throw new RunCancelledException("Persistent shell command was interrupted.", exception);
                }
            }
        }
    }

    private void readStdout(InputStream stream) {
        byte[] chunk = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                synchronized (outputMonitor) {
                    int hardCap = Math.max(maxOutputBytes * 4, maxOutputBytes + 1024);
                    int remaining = hardCap - stdout.size();
                    if (remaining > 0) {
                        stdout.write(chunk, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        overflow = true;
                    }
                    outputMonitor.notifyAll();
                }
            }
        } catch (IOException exception) {
            synchronized (outputMonitor) {
                stdoutClosed = true;
                outputMonitor.notifyAll();
            }
        } finally {
            synchronized (outputMonitor) {
                stdoutClosed = true;
                outputMonitor.notifyAll();
            }
        }
    }

    private void readStderr(InputStream stream) {
        byte[] chunk = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                if (read > 0) {
                    synchronized (outputMonitor) {
                        stderr.append(chunk, read);
                    }
                }
            }
        } catch (IOException exception) {
            // Closing the session intentionally closes the stream.
        }
    }

    private Captured capture(int stdoutEnd) {
        synchronized (outputMonitor) {
            byte[] bytes = stdout.toByteArray();
            int end = stdoutEnd < 0 ? bytes.length : stdoutEnd;
            String stdoutText = new String(bytes, 0, end, StandardCharsets.UTF_8).stripTrailing();
            HeadTailBuffer boundedStdout = new HeadTailBuffer(maxOutputBytes);
            byte[] encoded = stdoutText.getBytes(StandardCharsets.UTF_8);
            boundedStdout.append(encoded, encoded.length);
            HeadTailBuffer.CapturedOutput stdoutCapture = boundedStdout.capture();
            HeadTailBuffer.CapturedOutput stderrCapture = stderr.capture();
            return new Captured(
                    stdoutCapture.text(), stderrCapture.text(), stdoutCapture.truncated() || stderrCapture.truncated());
        }
    }

    private void resetOutput() {
        synchronized (outputMonitor) {
            stdout.reset();
            stderr = new HeadTailBuffer(maxOutputBytes);
            stdoutClosed = false;
            overflow = false;
        }
    }

    private void writeRaw(String script) {
        try {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException exception) {
            throw new ShellExecutionException("Persistent shell session is no longer writable.", exception);
        }
    }

    private void closeProcess() {
        synchronized (lifecycleLock) {
            Process current = process;
            process = null;
            if (current == null) {
                return;
            }
            try {
                if (current.isAlive()) {
                    try {
                        stdin.write("exit\n".getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                        stdin.close();
                    } catch (IOException exception) {
                        // The process may already have closed its input pipe.
                    }
                    if (!current.waitFor(CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                        ShellProcesses.killTree(current);
                        current.waitFor();
                    }
                }
            } catch (InterruptedException exception) {
                ShellProcesses.killTree(current);
                Thread.currentThread().interrupt();
            } finally {
                joinReader(stdoutReader);
                joinReader(stderrReader);
                stdin = null;
                stdoutReader = null;
                stderrReader = null;
            }
        }
    }

    private static void joinReader(CompletableFuture<Void> reader) {
        if (reader != null) {
            try {
                reader.join();
            } catch (RuntimeException exception) {
                // Stream closure during process teardown is expected.
            }
        }
    }

    private static int indexOf(byte[] value, byte[] needle) {
        outer:
        for (int index = 0; index <= value.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static Integer parseExitCode(byte[] value, int offset) {
        if (offset >= value.length || value[offset] != '_') {
            return null;
        }
        int index = offset + 1;
        int end = index;
        while (end < value.length && value[end] >= '0' && value[end] <= '9') {
            end++;
        }
        if (end == index || (end == value.length && value[end - 1] != '\n')) {
            return null;
        }
        try {
            return Integer.parseInt(new String(value, index, end - index, StandardCharsets.US_ASCII));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RunCancelledException("Persistent shell command was interrupted.", exception);
        }
    }

    private record Boundary(int sentinelIndex, int exitCode, boolean timedOut, boolean overflow) {}

    private record Captured(String stdout, String stderr, boolean truncated) {}

    private static final class SecureRandomHolder {
        private static final java.security.SecureRandom INSTANCE = new java.security.SecureRandom();
    }
}
