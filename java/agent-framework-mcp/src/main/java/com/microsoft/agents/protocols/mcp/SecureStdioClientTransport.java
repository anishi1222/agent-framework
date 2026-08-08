// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

final class SecureStdioClientTransport implements McpClientTransport {
    private final MCPStdioTransport configuration;

    private final McpJsonMapper jsonMapper;

    private final MCPLimits limits;

    private final AtomicBoolean closing = new AtomicBoolean();

    private final Semaphore pendingWrites;

    private final Semaphore inboundHandlers;

    private final Object outputLock = new Object();

    private final Object lifecycleLock = new Object();

    private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("mcp-stdio-input-", 0).factory());

    private final ExecutorService errorExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("mcp-stdio-error-", 0).factory());

    private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("mcp-stdio-output-", 0).factory());

    private final Scheduler outputScheduler = Schedulers.fromExecutorService(outputExecutor);

    private final AtomicReference<String> lastStandardError = new AtomicReference<>();

    private volatile Consumer<Throwable> exceptionHandler = ignored -> {};

    private volatile Process process;

    SecureStdioClientTransport(MCPStdioTransport configuration, McpJsonMapper jsonMapper, MCPLimits limits) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.limits = Objects.requireNonNull(limits, "limits");
        pendingWrites = new Semaphore(limits.maxEventBuffer());
        inboundHandlers = new Semaphore(limits.maxEventBuffer());
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        Objects.requireNonNull(handler, "handler");
        return Mono.fromRunnable(() -> start(handler))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> handler) {
        exceptionHandler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        Objects.requireNonNull(message, "message");
        return Mono.defer(() -> {
                    if (closing.get()) {
                        return Mono.error(new McpTransportException("MCP stdio transport is closed."));
                    }
                    if (!pendingWrites.tryAcquire()) {
                        return Mono.error(new McpTransportException("MCP stdio outbound buffer limit was reached."));
                    }
                    return Mono.fromRunnable(() -> write(message))
                            .subscribeOn(outputScheduler)
                            .doFinally(ignored -> pendingWrites.release())
                            .then();
                })
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(this::closeTransport)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return jsonMapper.convertValue(data, typeRef);
    }

    private void start(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        synchronized (lifecycleLock) {
            if (closing.get()) {
                throw new McpTransportException("MCP stdio transport is closed.");
            }
            if (process != null) {
                throw new McpTransportException("MCP stdio transport is already connected.");
            }
            List<String> command = new ArrayList<>();
            command.add(configuration.command());
            command.addAll(configuration.arguments());
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> childEnvironment = new LinkedHashMap<>();
            configuration.inheritedEnvironmentAllowlist().forEach(name -> {
                String value = System.getenv(name);
                if (value != null && !value.startsWith("()")) {
                    childEnvironment.put(name, value);
                }
            });
            childEnvironment.putAll(configuration.environment());
            builder.environment().clear();
            builder.environment().putAll(childEnvironment);
            if (configuration.workingDirectory() != null) {
                builder.directory(revalidateWorkingDirectory().toFile());
            }
            Process started;
            try {
                started = builder.start();
            } catch (IOException exception) {
                throw new McpTransportException(
                        "Unable to start the configured MCP child process. Verify the executable "
                                + "and working-directory policy.",
                        exception);
            }
            process = started;
            try {
                inputExecutor.execute(() -> readMessages(handler));
                errorExecutor.execute(this::readErrors);
            } catch (RuntimeException failure) {
                process = null;
                terminateProcess(started, configuration.shutdownTimeout());
                throw new McpTransportException("Unable to start MCP stdio transport readers.", failure);
            }
        }
    }

    private void readMessages(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (InputStream input = process.getInputStream()) {
            while (!closing.get()) {
                String line = readBoundedLine(input, limits.maxPayloadBytes());
                if (line == null) {
                    if (!closing.get()) {
                        exceptionHandler.accept(new EOFException("MCP child process closed stdout."));
                    }
                    return;
                }
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                inboundHandlers.acquire();
                Mono<McpSchema.JSONRPCMessage> handled;
                try {
                    handled = Objects.requireNonNull(handler.apply(Mono.just(message)), "inbound handler result");
                } catch (RuntimeException failure) {
                    inboundHandlers.release();
                    throw failure;
                }
                handled.doFinally(ignored -> inboundHandlers.release())
                        .subscribe(
                                ignored -> {}, failure -> exceptionHandler.accept(redactedTransportFailure(failure)));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (!closing.get()) {
                exceptionHandler.accept(redactedTransportFailure(exception));
            }
        }
    }

    private void readErrors() {
        try (InputStream input = process.getErrorStream()) {
            while (!closing.get()) {
                String line = readBoundedLine(input, Math.min(limits.maxPayloadBytes(), 65_536));
                if (line == null) {
                    return;
                }
                if (!line.isBlank()) {
                    lastStandardError.set(MCPRedactor.redact(line));
                    org.slf4j.LoggerFactory.getLogger(SecureStdioClientTransport.class)
                            .debug("MCP child stderr: {}", lastStandardError.get());
                }
            }
        } catch (IOException exception) {
            if (!closing.get()) {
                exceptionHandler.accept(new McpTransportException("Unable to read MCP child stderr.", exception));
            }
        }
    }

    private void write(McpSchema.JSONRPCMessage message) {
        Process active = process;
        if (active == null || !active.isAlive()) {
            if (active != null && lastStandardError.get() == null) {
                try {
                    active.onExit().get(100, TimeUnit.MILLISECONDS);
                    for (int attempt = 0; attempt < 10 && lastStandardError.get() == null; attempt++) {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                    // The actionable failure below remains valid without child diagnostics.
                }
            }
            String detail = lastStandardError.get();
            throw new McpTransportException(
                    detail == null
                            ? "MCP child process is not running."
                            : "MCP child process is not running: " + detail);
        }
        try {
            String json = jsonMapper.writeValueAsString(message);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limits.maxPayloadBytes()) {
                throw new McpTransportException("MCP outbound message exceeds the configured payload limit.");
            }
            synchronized (outputLock) {
                OutputStream output = active.getOutputStream();
                output.write(bytes);
                output.write('\n');
                output.flush();
            }
        } catch (IOException exception) {
            throw new McpTransportException("Unable to write an MCP child-process message.", exception);
        }
    }

    private void closeTransport() {
        Process active;
        synchronized (lifecycleLock) {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            active = process;
        }
        if (active != null) {
            closeQuietly(active.getOutputStream());
            terminateProcess(active, configuration.shutdownTimeout());
        }
        outputScheduler.dispose();
        inputExecutor.shutdownNow();
        errorExecutor.shutdownNow();
        outputExecutor.shutdownNow();
    }

    private static void terminateProcess(Process process, Duration timeout) {
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        observeDescendants(process, descendants);
        descendants.values().forEach(ProcessHandle::destroy);
        process.destroy();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                observeDescendants(process, descendants);
                descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
                Thread.sleep(20);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            observeDescendants(process, descendants);
        }
        descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            for (ProcessHandle descendant : descendants.values()) {
                if (descendant.isAlive()) {
                    descendant.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // Every surviving process has already received forced termination.
        }
    }

    private static void observeDescendants(Process process, Map<Long, ProcessHandle> descendants) {
        process.descendants().forEach(handle -> descendants.putIfAbsent(handle.pid(), handle));
    }

    private java.nio.file.Path revalidateWorkingDirectory() {
        try {
            java.nio.file.Path resolved = configuration.workingDirectory().toRealPath();
            boolean allowed = configuration.allowedWorkingDirectories().stream().anyMatch(resolved::startsWith);
            if (!allowed) {
                throw new McpTransportException("Working directory no longer satisfies its allowlist.");
            }
            return resolved;
        } catch (IOException exception) {
            throw new McpTransportException("Working directory is no longer resolvable.", exception);
        }
    }

    private static String readBoundedLine(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 4096));
        while (true) {
            int next = input.read();
            if (next == -1) {
                return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            }
            if (next == '\n') {
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (next != '\r') {
                if (bytes.size() >= maximumBytes) {
                    throw new IOException("MCP stdio line exceeds the configured payload limit.");
                }
                bytes.write(next);
            }
        }
    }

    private static RuntimeException redactedTransportFailure(Throwable failure) {
        return new McpTransportException(
                "MCP stdio transport failed: " + MCPRedactor.redact(failure.getMessage()), failure);
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
            // Best effort before deterministic process termination.
        }
    }
}
