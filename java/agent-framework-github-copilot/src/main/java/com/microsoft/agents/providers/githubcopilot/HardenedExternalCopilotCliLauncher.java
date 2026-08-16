// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Optionally launches the CLI as an external loopback server for process-tree controls only.
 *
 * <p>This class never reads, writes, or interprets Copilot RPC messages. The official SDK connects
 * to the announced loopback endpoint and remains the sole owner of handshake and protocol semantics.
 */
final class HardenedExternalCopilotCliLauncher implements AutoCloseable {
    private static final Pattern PORT_ANNOUNCEMENT =
            Pattern.compile("listening on port\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final GitHubCopilotClientOptions options;

    private final List<String> commandPrefixArguments;

    private final Object stderrLock = new Object();

    private final StringBuilder stderr = new StringBuilder();

    private volatile Process process;

    HardenedExternalCopilotCliLauncher(GitHubCopilotClientOptions options) {
        this(options, List.of());
    }

    HardenedExternalCopilotCliLauncher(GitHubCopilotClientOptions options, List<String> commandPrefixArguments) {
        this.options = options;
        this.commandPrefixArguments = List.copyOf(commandPrefixArguments);
    }

    GitHubCopilotExternalServer start() {
        if (process != null) {
            throw new IllegalStateException("Copilot CLI process already started.");
        }
        String connectionToken = UUID.randomUUID().toString();
        ArrayList<String> command = new ArrayList<>();
        command.add(options.cliExecutable().toString());
        command.addAll(commandPrefixArguments);
        command.add("--server");
        command.add("--no-auto-update");
        command.add("--log-level");
        command.add("error");
        command.add("--port");
        command.add("0");
        if (options.credential() != null) {
            command.add("--auth-token-env");
            command.add("COPILOT_SDK_AUTH_TOKEN");
        }
        if (!options.useLoggedInUser()) {
            command.add("--no-auto-login");
        }
        command.add("--session-idle-timeout");
        command.add(Long.toString(options.idleTimeout().toSeconds()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(options.workingDirectory().toFile());
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(options.environment());
        environment.remove("NODE_DEBUG");
        environment.put("COPILOT_CONNECTION_TOKEN", connectionToken);
        if (options.credential() != null) {
            environment.put("COPILOT_SDK_AUTH_TOKEN", options.credential().reveal());
        }
        if (options.copilotHome() != null) {
            environment.put("COPILOT_HOME", options.copilotHome().toString());
        }
        applyTelemetry(environment, options.telemetry());

        CountDownLatch listening = new CountDownLatch(1);
        AtomicReference<Integer> announcedPort = new AtomicReference<>();
        AtomicReference<Throwable> outputFailure = new AtomicReference<>();
        try {
            process = builder.start();
            startStderrReader(process);
            startStdoutReader(process, announcedPort, listening, outputFailure);
            boolean announced = listening.await(options.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!announced) {
                Throwable failure = outputFailure.get();
                String detail = sanitizedStderr();
                close();
                throw new GitHubCopilotProviderException(
                        "Copilot CLI did not announce its loopback server before the startup timeout."
                                + (detail.isEmpty() ? "" : " stderr: " + detail),
                        failure,
                        "startup",
                        "port_announcement_timeout");
            }
            if (!process.isAlive()) {
                String detail = sanitizedStderr();
                close();
                throw new GitHubCopilotProviderException(
                        "Copilot CLI exited during startup." + (detail.isEmpty() ? "" : " stderr: " + detail),
                        outputFailure.get(),
                        "startup",
                        "process_exit");
            }
            Integer port = announcedPort.get();
            if (port == null) {
                close();
                throw new GitHubCopilotProviderException(
                        "Copilot CLI announced an invalid loopback server port.",
                        null,
                        "startup",
                        "port_announcement_invalid");
            }
            return new GitHubCopilotExternalServer("127.0.0.1", port, connectionToken);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
            throw new GitHubCopilotProviderException(
                    "Copilot CLI startup was interrupted.", exception, "startup", "interrupted");
        } catch (IOException exception) {
            close();
            throw new GitHubCopilotProviderException(
                    "Copilot CLI process could not be started.", exception, "startup", "process_start");
        }
    }

    String sanitizedStderr() {
        synchronized (stderrLock) {
            return stderr.toString();
        }
    }

    boolean isAliveForTesting() {
        Process current = process;
        return current != null && current.isAlive();
    }

    @Override
    public void close() {
        Process current = process;
        process = null;
        if (current == null) {
            return;
        }
        ArrayList<ProcessHandle> descendants =
                new ArrayList<>(current.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        descendants.forEach(ProcessHandle::destroy);
        current.destroy();
        await(current, options.closeTimeout().dividedBy(2));
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (current.isAlive()) {
            current.destroyForcibly();
        }
        await(current, options.closeTimeout().dividedBy(2));
    }

    private void startStdoutReader(
            Process current,
            AtomicReference<Integer> announcedPort,
            CountDownLatch listening,
            AtomicReference<Throwable> outputFailure) {
        Thread.ofVirtual().name("github-copilot-cli-stdout").start(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    var matcher = PORT_ANNOUNCEMENT.matcher(line);
                    if (matcher.find()) {
                        int port = Integer.parseInt(matcher.group(1));
                        if (port < 1 || port > 65_535) {
                            throw new IOException("Copilot CLI announced an invalid port.");
                        }
                        announcedPort.compareAndSet(null, port);
                        listening.countDown();
                    }
                }
                if (listening.getCount() != 0) {
                    outputFailure.compareAndSet(
                            null, new IOException("Copilot CLI stdout closed before port announcement."));
                }
            } catch (Throwable failure) {
                outputFailure.compareAndSet(null, failure);
            } finally {
                if (!current.isAlive()) {
                    listening.countDown();
                }
            }
        });
    }

    private void startStderrReader(Process current) {
        Thread.ofVirtual().name("github-copilot-cli-stderr").start(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader)) != null) {
                    appendStderr(redact(line));
                }
            } catch (IOException exception) {
                appendStderr("stderr reader failed");
            }
        });
    }

    private String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = reader.read();
            if (value < 0) {
                return line.isEmpty() ? null : line.toString();
            }
            if (value == '\n') {
                return line.toString();
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > options.limits().maxProcessOutputLineBytes()) {
                throw new IOException("CLI output line exceeds configured maximum.");
            }
        }
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            String combined = stderr.isEmpty() ? line : stderr + "\n" + line;
            byte[] encoded = combined.getBytes(StandardCharsets.UTF_8);
            int start = Math.max(0, encoded.length - options.limits().maxStderrBytes());
            while (start < encoded.length && (encoded[start] & 0xc0) == 0x80) {
                start++;
            }
            stderr.setLength(0);
            stderr.append(new String(encoded, start, encoded.length - start, StandardCharsets.UTF_8));
        }
    }

    private String redact(String value) {
        String redacted = value;
        if (options.credential() != null) {
            redacted = redacted.replace(options.credential().reveal(), "[REDACTED]");
        }
        return redacted.replaceAll("(?i)(authorization|token|secret|password)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    }

    private static void applyTelemetry(Map<String, String> environment, GitHubCopilotTelemetryConfig telemetry) {
        if (telemetry == null) {
            return;
        }
        environment.put("COPILOT_OTEL_ENABLED", "true");
        if (telemetry.otlpEndpoint() != null) {
            environment.put(
                    "OTEL_EXPORTER_OTLP_ENDPOINT", telemetry.otlpEndpoint().toString());
        }
        if (telemetry.otlpProtocol() != null) {
            environment.put("OTEL_EXPORTER_OTLP_PROTOCOL", telemetry.otlpProtocol());
        }
        if (telemetry.filePath() != null) {
            environment.put(
                    "COPILOT_OTEL_FILE_EXPORTER_PATH", telemetry.filePath().toString());
        }
        if (telemetry.exporterType() != null) {
            environment.put("COPILOT_OTEL_EXPORTER_TYPE", telemetry.exporterType());
        }
        if (telemetry.sourceName() != null) {
            environment.put("COPILOT_OTEL_SOURCE_NAME", telemetry.sourceName());
        }
        if (telemetry.captureContent() != null) {
            environment.put(
                    "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT",
                    telemetry.captureContent().toString());
        }
    }

    private static void await(Process process, Duration timeout) {
        try {
            process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
