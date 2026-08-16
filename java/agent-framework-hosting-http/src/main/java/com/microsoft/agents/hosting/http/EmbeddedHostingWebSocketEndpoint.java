// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class EmbeddedHostingWebSocketEndpoint extends Endpoint {
    private final HostingWebSocketProtocol protocol;

    private volatile HostingWebSocketConnection connection;

    private EmbeddedHostingWebSocketEndpoint(HostingWebSocketProtocol protocol) {
        this.protocol = java.util.Objects.requireNonNull(protocol, "protocol");
    }

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        if (!HostingWebSocketProtocol.SUBPROTOCOL.equals(session.getNegotiatedSubprotocol())
                || !(session.getUserPrincipal() instanceof HostingWebSocketUpgradeFilter.HostingPrincipalCarrier)) {
            close(session, 1008, "unauthorized");
            return;
        }
        HostingWebSocketUpgradeFilter.HostingPrincipalCarrier carrier =
                (HostingWebSocketUpgradeFilter.HostingPrincipalCarrier) session.getUserPrincipal();
        int maxFrameBytes =
                ((EndpointConfigurator) config.getUserProperties().get(EndpointConfigurator.KEY)).maxFrameBytes();
        session.setMaxTextMessageBufferSize(maxFrameBytes);
        session.setMaxBinaryMessageBufferSize(maxFrameBytes);
        session.getAsyncRemote()
                .setSendTimeout(carrier.context().cancellation().isCancellationRequested() ? 1 : 30_000);
        connection = protocol.open(carrier.context(), new TomcatPeer(session));
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) connection::receiveText);
        session.addMessageHandler(
                PongMessage.class, (MessageHandler.Whole<PongMessage>) ignored -> connection.receivePong());
        session.addMessageHandler(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>)
                ignored -> close(session, 1003, "binary unsupported"));
    }

    @Override
    public void onClose(Session session, CloseReason closeReason) {
        HostingWebSocketConnection current = connection;
        if (current != null) {
            current.peerClosed();
        }
    }

    @Override
    public void onError(Session session, Throwable throwable) {
        HostingWebSocketConnection current = connection;
        if (current != null) {
            current.peerClosed();
        }
    }

    static ServerEndpointConfig config(HostingWebSocketProtocol protocol, int maxFrameBytes) {
        EndpointConfigurator configurator = new EndpointConfigurator(protocol, maxFrameBytes);
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(
                        EmbeddedHostingWebSocketEndpoint.class, HostingHttpHandler.WEBSOCKET_PATH)
                .subprotocols(List.of(HostingWebSocketProtocol.SUBPROTOCOL))
                .configurator(configurator)
                .build();
        config.getUserProperties().put(EndpointConfigurator.KEY, configurator);
        return config;
    }

    private static void close(Session session, int code, String reason) {
        try {
            if (session.isOpen()) {
                session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
            }
        } catch (IOException ignored) {
            // The peer has already gone away.
        }
    }

    private static final class EndpointConfigurator extends ServerEndpointConfig.Configurator {
        private static final String KEY = EndpointConfigurator.class.getName();

        private final HostingWebSocketProtocol protocol;

        private final int maxFrameBytes;

        private EndpointConfigurator(HostingWebSocketProtocol protocol, int maxFrameBytes) {
            this.protocol = protocol;
            this.maxFrameBytes = maxFrameBytes;
        }

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            if (!EmbeddedHostingWebSocketEndpoint.class.equals(endpointClass)) {
                throw new InstantiationException("Unexpected endpoint class.");
            }
            return endpointClass.cast(new EmbeddedHostingWebSocketEndpoint(protocol));
        }

        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            return requested.size() == 1 && HostingWebSocketProtocol.SUBPROTOCOL.equals(requested.getFirst())
                    ? HostingWebSocketProtocol.SUBPROTOCOL
                    : "";
        }

        @Override
        public List<jakarta.websocket.Extension> getNegotiatedExtensions(
                List<jakarta.websocket.Extension> installed, List<jakarta.websocket.Extension> requested) {
            return List.of();
        }

        private int maxFrameBytes() {
            return maxFrameBytes;
        }
    }

    private static final class TomcatPeer implements HostingWebSocketPeer {
        private final Session session;

        private TomcatPeer(Session session) {
            this.session = session;
        }

        @Override
        public CompletionStage<Void> sendTextAsync(String text) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (!session.isOpen()) {
                result.completeExceptionally(new IOException("WebSocket session is closed."));
                return result;
            }
            session.getAsyncRemote().sendText(text, sendResult -> {
                if (sendResult.isOK()) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(sendResult.getException());
                }
            });
            return result;
        }

        @Override
        public CompletionStage<Void> pingAsync(byte[] payload) {
            try {
                session.getBasicRemote().sendPing(ByteBuffer.wrap(payload.clone()));
                return CompletableFuture.completedFuture(null);
            } catch (IOException | IllegalArgumentException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public CompletionStage<Void> closeAsync(int code, String reason) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), reason));
                }
                return CompletableFuture.completedFuture(null);
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }
}
