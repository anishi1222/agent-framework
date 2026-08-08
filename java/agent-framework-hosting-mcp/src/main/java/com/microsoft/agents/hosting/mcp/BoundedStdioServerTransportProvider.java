// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPLimits;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpTransportException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

final class BoundedStdioServerTransportProvider implements McpServerTransportProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedStdioServerTransportProvider.class);

    private final McpJsonMapper jsonMapper;

    private final InputStream input;

    private final OutputStream output;

    private final MCPLimits limits;

    private final ServerTransport transport;

    private volatile McpServerSession session;

    BoundedStdioServerTransportProvider(
            McpJsonMapper jsonMapper, InputStream input, OutputStream output, MCPLimits limits) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.limits = Objects.requireNonNull(limits, "limits");
        transport = new ServerTransport();
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        Objects.requireNonNull(sessionFactory, "sessionFactory");
        if (session != null) {
            throw new IllegalStateException("MCP stdio session factory is already configured.");
        }
        session = sessionFactory.create(transport);
        transport.start();
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        McpServerSession active = session;
        if (active == null) {
            return Mono.error(new IllegalStateException("MCP stdio session is not initialized."));
        }
        return active.sendNotification(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        McpServerSession active = session;
        if (active == null || !active.getId().equals(sessionId)) {
            return Mono.empty();
        }
        return active.sendNotification(method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        McpServerSession active = session;
        return active == null ? transport.closeGracefully() : active.closeGracefully();
    }

    private final class ServerTransport implements McpServerTransport {
        private final AtomicBoolean closing = new AtomicBoolean();

        private final Semaphore inboundSlots = new Semaphore(limits.maxEventBuffer());

        private final Semaphore outboundSlots = new Semaphore(limits.maxEventBuffer());

        private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("mcp-server-stdio-input-", 0).factory());

        private final ExecutorService outputExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("mcp-server-stdio-output-", 0).factory());

        private final Scheduler outputScheduler = Schedulers.fromExecutorService(outputExecutor);

        private final Object outputLock = new Object();

        private void start() {
            inputExecutor.execute(this::readLoop);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            Objects.requireNonNull(message, "message");
            return Mono.defer(() -> {
                if (closing.get()) {
                    return Mono.error(new McpTransportException("MCP stdio server transport is closed."));
                }
                if (!outboundSlots.tryAcquire()) {
                    return Mono.error(new McpTransportException("MCP stdio server outbound buffer limit was reached."));
                }
                return Mono.fromRunnable(() -> write(message))
                        .subscribeOn(outputScheduler)
                        .doFinally(ignored -> outboundSlots.release())
                        .then();
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::closeTransport);
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new LineBoundedInputStream(input, limits.maxPayloadBytes()), StandardCharsets.UTF_8))) {
                String line;
                while (!closing.get() && (line = reader.readLine()) != null) {
                    inboundSlots.acquire();
                    McpSchema.JSONRPCMessage message;
                    try {
                        message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                    } catch (RuntimeException | IOException failure) {
                        inboundSlots.release();
                        throw failure;
                    }
                    session.handle(message)
                            .doFinally(ignored -> inboundSlots.release())
                            .subscribe(ignored -> {}, failure -> {
                                LOGGER.warn("MCP stdio server message handling failed; closing the transport.");
                                closeTransport();
                            });
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                if (!closing.get()) {
                    LOGGER.warn("MCP stdio server input failed; closing the transport.");
                }
            } finally {
                closeTransport();
            }
        }

        private void write(McpSchema.JSONRPCMessage message) {
            try {
                byte[] bytes = jsonMapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > limits.maxPayloadBytes()) {
                    throw new McpTransportException("MCP stdio server response exceeds the payload limit.");
                }
                synchronized (outputLock) {
                    output.write(bytes);
                    output.write('\n');
                    output.flush();
                }
            } catch (IOException failure) {
                throw new McpTransportException("Unable to write an MCP stdio server message.", failure);
            }
        }

        private void closeTransport() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // Best effort to unblock the input reader.
            }
            outputScheduler.dispose();
            inputExecutor.shutdownNow();
            outputExecutor.shutdownNow();
        }
    }
}
