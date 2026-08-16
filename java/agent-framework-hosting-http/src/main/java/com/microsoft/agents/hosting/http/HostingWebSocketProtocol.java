// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingResumeRequest;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRun;
import com.microsoft.agents.hosting.HostingRunRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Implements typed full-duplex Java-hosting frames independently of a WebSocket server library.
 */
public final class HostingWebSocketProtocol implements AutoCloseable {
    /** Required exact WebSocket subprotocol. */
    public static final String SUBPROTOCOL = "agent-framework-hosting.v1";

    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final Set<String> START_FIELDS =
            Set.of("version", "type", "operationId", "kind", "routeId", "request");

    private static final Set<String> RESUME_FIELDS =
            Set.of("version", "type", "operationId", "kind", "routeId", "runId", "request");

    private static final Set<String> CONTROL_FIELDS = Set.of("version", "type", "operationId");

    private static final Set<String> DEMAND_FIELDS = Set.of("version", "type", "operationId", "count");

    private static final Set<String> CLOSE_FIELDS = Set.of("version", "type");

    private final HostingDispatcher dispatcher;

    private final HostingJsonCodec codec;

    private final HostingHttpServerOptions options;

    private final ScheduledExecutorService scheduler;

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a shared protocol runtime.
     *
     * @param dispatcher hosting dispatcher
     * @param codec strict shared codec
     * @param options transport options
     */
    public HostingWebSocketProtocol(
            HostingDispatcher dispatcher, HostingJsonCodec codec, HostingHttpServerOptions options) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.options = Objects.requireNonNull(options, "options");
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon(true)
                .name("agent-framework-hosting-websocket-idle")
                .factory());
    }

    /**
     * Opens one authenticated protocol connection.
     *
     * @param context trusted handshake context
     * @param peer server-library peer adapter
     * @return connection
     */
    public HostingWebSocketConnection open(HostingRequestContext context, HostingWebSocketPeer peer) {
        if (closed.get()) {
            throw new HostingException(HostingErrorCode.CONFLICT, "WebSocket protocol is closed.");
        }
        Connection connection =
                new Connection(Objects.requireNonNull(context, "context"), Objects.requireNonNull(peer, "peer"));
        connections.add(connection);
        connection.startIdleChecks();
        return connection;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(connections).forEach(Connection::close);
        scheduler.shutdownNow();
    }

    private final class Connection implements HostingWebSocketConnection {
        private final Object operationLock = new Object();

        private final Object outboundOrderLock = new Object();

        private final HostingRequestContext context;

        private final HostingWebSocketPeer peer;

        private final SerialSender sender;

        private final AtomicBoolean open = new AtomicBoolean(true);

        private final AtomicReference<Operation> operation = new AtomicReference<>();

        private volatile boolean starting;

        private volatile long lastActivityNanos = System.nanoTime();

        private java.util.concurrent.ScheduledFuture<?> idleCheck;

        private Connection(HostingRequestContext context, HostingWebSocketPeer peer) {
            this.context = context;
            this.peer = peer;
            sender = new SerialSender(
                    peer, options.limits().maxWebSocketBufferedMessages(), this::outboundOverflow, this::peerClosed);
        }

        @Override
        public void receiveText(String text) {
            if (!open.get()) {
                return;
            }
            lastActivityNanos = System.nanoTime();
            try {
                byte[] bytes = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
                if (bytes.length > options.limits().maxWebSocketFrameBytes()) {
                    throw new HostingException(
                            HostingErrorCode.PAYLOAD_TOO_LARGE, "WebSocket message exceeds maxWebSocketFrameBytes.");
                }
                StateValue.ObjectValue frame = codec.decodeObject(bytes);
                requireVersion(frame);
                String type = requireString(frame, "type");
                switch (type) {
                    case "start" -> start(frame);
                    case "resume" -> resume(frame);
                    case "demand" -> demand(frame);
                    case "cancel" -> cancel(frame);
                    case "close" -> closeFrame(frame);
                    default ->
                        throw new HostingException(
                                HostingErrorCode.MALFORMED_REQUEST, "Unknown WebSocket client frame type.");
                }
            } catch (HostingException failure) {
                sendError(null, failure.error());
                if (failure.error().code() == HostingErrorCode.MALFORMED_REQUEST
                        || failure.error().code() == HostingErrorCode.PAYLOAD_TOO_LARGE) {
                    closePeer(
                            failure.error().code().webSocketCloseCode(),
                            failure.error().code().value());
                }
            } catch (RuntimeException failure) {
                HostingError error = HostingError.of(HostingErrorCode.INTERNAL_ERROR, "WebSocket frame failed.");
                sendError(null, error);
                closePeer(error.code().webSocketCloseCode(), error.code().value());
            }
        }

        @Override
        public void receivePong() {
            lastActivityNanos = System.nanoTime();
        }

        @Override
        public void peerClosed() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            connections.remove(this);
            cancelIdleCheck();
            Operation current = operation.getAndSet(null);
            if (current != null) {
                current.run().cancel();
            }
            context.cancellation().cancel();
            sender.stop();
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            connections.remove(this);
            cancelIdleCheck();
            Operation current = operation.getAndSet(null);
            if (current != null) {
                current.run().cancel();
            }
            context.cancellation().cancel();
            sender.stop();
            peer.closeAsync(1001, "server shutdown");
        }

        private void start(StateValue.ObjectValue frame) {
            rejectUnknown(frame, START_FIELDS, "start");
            String operationId = operationId(frame);
            HostingRouteKind kind = kind(frame);
            String routeId = requireString(frame, "routeId");
            StateValue.ObjectValue requestValue = requireObject(frame, "request");
            HostingRunRequest request = codec.decodeRunRequest(requestValue);
            begin(operationId, kind, routeId, () -> dispatcher.startStreamingAsync(context, kind, routeId, request));
        }

        private void resume(StateValue.ObjectValue frame) {
            rejectUnknown(frame, RESUME_FIELDS, "resume");
            String operationId = operationId(frame);
            HostingRouteKind kind = kind(frame);
            String routeId = requireString(frame, "routeId");
            String runId = requireString(frame, "runId");
            HostingResumeRequest request = codec.decodeResumeRequest(requireObject(frame, "request"));
            begin(
                    operationId,
                    kind,
                    routeId,
                    () -> dispatcher.resumeStreamingAsync(context, kind, routeId, runId, request));
        }

        private void begin(
                String operationId,
                HostingRouteKind kind,
                String routeId,
                Supplier<CompletionStage<HostingRun>> starter) {
            synchronized (operationLock) {
                if (starting || operation.get() != null) {
                    throw new HostingException(
                            HostingErrorCode.CONFLICT,
                            "Only one active WebSocket operation is allowed per connection.");
                }
                starting = true;
            }
            CompletionStage<HostingRun> stage;
            try {
                stage = Objects.requireNonNull(starter.get(), "WebSocket run stage");
            } catch (RuntimeException failure) {
                synchronized (operationLock) {
                    starting = false;
                }
                throw failure;
            }
            stage.whenComplete((run, failure) -> {
                if (failure != null) {
                    synchronized (operationLock) {
                        starting = false;
                    }
                    sendError(operationId, error(failure));
                    return;
                }
                Operation value = new Operation(operationId, kind, routeId, run);
                boolean installed;
                synchronized (operationLock) {
                    installed = starting && open.get() && operation.get() == null;
                    if (installed) {
                        operation.set(value);
                    }
                    starting = false;
                }
                if (!installed) {
                    run.cancel();
                    if (open.get()) {
                        sendError(
                                operationId,
                                HostingError.of(
                                        HostingErrorCode.CONFLICT,
                                        "Only one active WebSocket operation is allowed per connection."));
                    }
                    return;
                }
                try {
                    if (!sendFrame(startedFrame(value))) {
                        return;
                    }
                } catch (RuntimeException failureValue) {
                    operation.compareAndSet(value, null);
                    run.cancel();
                    closeForEncodingFailure(failureValue);
                    return;
                }
                run.events().subscribe(new EventSubscriber(value));
                run.terminalAsync().whenComplete((outcome, terminalFailure) -> {
                    HostingOutcome safeOutcome = outcome == null
                            ? HostingOutcome.failed(
                                    run.runId(),
                                    HostingError.of(
                                            HostingErrorCode.INTERNAL_ERROR, "Hosted WebSocket operation failed."))
                            : outcome;
                    try {
                        synchronized (outboundOrderLock) {
                            if (value.terminalSent().compareAndSet(false, true)) {
                                sendTerminalFrame(value, safeOutcome);
                            }
                        }
                    } finally {
                        operation.compareAndSet(value, null);
                    }
                });
            });
        }

        private void demand(StateValue.ObjectValue frame) {
            rejectUnknown(frame, DEMAND_FIELDS, "demand");
            Operation current = requireOperation(operationId(frame));
            long count = requirePositiveLong(frame, "count");
            if (count > options.limits().maxEventsPerRun()) {
                throw new HostingException(HostingErrorCode.UNPROCESSABLE, "WebSocket demand exceeds maxEventsPerRun.");
            }
            Flow.Subscription subscription = current.subscription().get();
            if (subscription == null) {
                throw new HostingException(
                        HostingErrorCode.CONFLICT, "WebSocket operation has not accepted demand yet.");
            }
            subscription.request(count);
        }

        private void cancel(StateValue.ObjectValue frame) {
            rejectUnknown(frame, CONTROL_FIELDS, "cancel");
            Operation current = requireOperation(operationId(frame));
            dispatcher
                    .cancelAsync(
                            context,
                            current.kind(),
                            current.routeId(),
                            current.run().runId())
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            sendError(current.operationId(), error(failure));
                        }
                    });
        }

        private void closeFrame(StateValue.ObjectValue frame) {
            rejectUnknown(frame, CLOSE_FIELDS, "close");
            closePeer(1000, "client close");
        }

        private Operation requireOperation(String operationId) {
            Operation current = operation.get();
            if (current == null) {
                throw new HostingException(HostingErrorCode.NOT_FOUND, "No active WebSocket operation was found.");
            }
            if (!current.operationId().equals(operationId)) {
                throw new HostingException(
                        HostingErrorCode.CONFLICT,
                        "WebSocket operation identifier does not match the active operation.");
            }
            return current;
        }

        private void sendError(String operationId, HostingError error) {
            try {
                LinkedHashMap<String, StateValue> frame = base("error");
                if (operationId != null) {
                    frame.put("operationId", StateValue.string(operationId));
                }
                frame.put("error", codec.errorBodyValue(error));
                sendFrame(StateValue.object(frame));
            } catch (RuntimeException failure) {
                closeForEncodingFailure(failure);
            }
        }

        private boolean sendFrame(StateValue value) {
            byte[] encoded = codec.encodePreparedValue(value);
            if (encoded.length > options.limits().maxWebSocketFrameBytes()) {
                outboundOverflow();
                return false;
            }
            sender.send(new String(encoded, StandardCharsets.UTF_8));
            return true;
        }

        private void sendTerminalFrame(Operation value, HostingOutcome outcome) {
            if (!open.get()) {
                dispatcher.discardUndeliveredOutcome(outcome);
                return;
            }
            try {
                if (!sendFrame(terminalFrame(value, outcome))) {
                    dispatcher.discardUndeliveredOutcome(outcome);
                }
            } catch (RuntimeException encodingFailure) {
                dispatcher.discardUndeliveredOutcome(outcome);
                HostingOutcome overflow = HostingOutcome.overflow(
                        value.run().runId(),
                        HostingError.of(
                                HostingErrorCode.OVERFLOW, "WebSocket terminal outcome exceeded transport limits."));
                try {
                    if (!sendFrame(terminalFrame(value, overflow)) && open.get()) {
                        closePeer(HostingErrorCode.OVERFLOW.webSocketCloseCode(), "overflow");
                    }
                } catch (RuntimeException fallbackFailure) {
                    closeForEncodingFailure(fallbackFailure);
                }
            }
        }

        private void closeForEncodingFailure(Throwable failure) {
            Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
            HostingErrorCode code = cause instanceof HostingException hosting
                    ? hosting.error().code()
                    : HostingErrorCode.INTERNAL_ERROR;
            int closeCode = code == HostingErrorCode.OVERFLOW || code == HostingErrorCode.PAYLOAD_TOO_LARGE
                    ? HostingErrorCode.OVERFLOW.webSocketCloseCode()
                    : HostingErrorCode.INTERNAL_ERROR.webSocketCloseCode();
            closePeer(closeCode, "frame encoding failed");
        }

        private StateValue startedFrame(Operation value) {
            LinkedHashMap<String, StateValue> frame = base("started");
            frame.put("operationId", StateValue.string(value.operationId()));
            frame.put("runId", StateValue.string(value.run().runId()));
            return StateValue.object(frame);
        }

        private StateValue terminalFrame(Operation value, HostingOutcome outcome) {
            LinkedHashMap<String, StateValue> frame = base("terminal");
            frame.put("operationId", StateValue.string(value.operationId()));
            frame.put("outcome", codec.outcomeValue(outcome));
            return StateValue.object(frame);
        }

        private LinkedHashMap<String, StateValue> base(String type) {
            LinkedHashMap<String, StateValue> frame = new LinkedHashMap<>();
            frame.put("version", StateValue.string(HostingJsonCodec.WIRE_VERSION));
            frame.put("type", StateValue.string(type));
            return frame;
        }

        private void startIdleChecks() {
            Duration idle = options.limits().idleTimeout();
            long period = Math.max(50L, Math.min(1_000L, idle.toMillis() / 2));
            idleCheck = scheduler.scheduleWithFixedDelay(
                    () -> {
                        if (!open.get()) {
                            return;
                        }
                        long elapsed = System.nanoTime() - lastActivityNanos;
                        if (elapsed >= idle.toNanos()) {
                            closePeer(1001, "idle timeout");
                            return;
                        }
                        peer.pingAsync("afh".getBytes(StandardCharsets.US_ASCII))
                                .exceptionally(failure -> {
                                    peerClosed();
                                    return null;
                                });
                    },
                    period,
                    period,
                    TimeUnit.MILLISECONDS);
        }

        private void closePeer(int code, String reason) {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            connections.remove(this);
            cancelIdleCheck();
            Operation current = operation.getAndSet(null);
            if (current != null) {
                current.run().cancel();
            }
            context.cancellation().cancel();
            sender.stop();
            peer.closeAsync(code, reason);
        }

        private void cancelIdleCheck() {
            java.util.concurrent.ScheduledFuture<?> task = idleCheck;
            if (task != null) {
                task.cancel(false);
            }
        }

        private void outboundOverflow() {
            Operation current = operation.getAndSet(null);
            if (current != null) {
                current.run().cancel();
            }
            closePeer(HostingErrorCode.OVERFLOW.webSocketCloseCode(), "overflow");
        }

        private final class EventSubscriber implements Flow.Subscriber<HostingEvent> {
            private final Operation owner;

            private EventSubscriber(Operation owner) {
                this.owner = owner;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                if (!owner.subscription().compareAndSet(null, subscription)) {
                    subscription.cancel();
                }
            }

            @Override
            public void onNext(HostingEvent item) {
                synchronized (outboundOrderLock) {
                    if (owner.terminalSent().get() || operation.get() != owner || !open.get()) {
                        return;
                    }
                    try {
                        LinkedHashMap<String, StateValue> frame = base("event");
                        frame.put("operationId", StateValue.string(owner.operationId()));
                        frame.put("event", codec.eventValue(item));
                        sendFrame(StateValue.object(frame));
                    } catch (RuntimeException failure) {
                        closeForEncodingFailure(failure);
                    }
                }
            }

            @Override
            public void onError(Throwable throwable) {
                synchronized (outboundOrderLock) {
                    if (!owner.terminalSent().get() && open.get()) {
                        sendError(owner.operationId(), error(throwable));
                    }
                }
            }

            @Override
            public void onComplete() {
                // The terminal stage sends the one typed terminal frame.
            }
        }
    }

    private static final class SerialSender {
        private final Object lock = new Object();

        private final HostingWebSocketPeer peer;

        private final int capacity;

        private final Runnable overflow;

        private final Runnable sendFailure;

        private final ArrayDeque<String> pending = new ArrayDeque<>();

        private boolean sending;

        private boolean stopped;

        private SerialSender(HostingWebSocketPeer peer, int capacity, Runnable overflow, Runnable sendFailure) {
            this.peer = peer;
            this.capacity = capacity;
            this.overflow = overflow;
            this.sendFailure = sendFailure;
        }

        private void send(String text) {
            boolean start = false;
            boolean overflowed = false;
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                if (pending.size() >= capacity) {
                    stopped = true;
                    pending.clear();
                    overflowed = true;
                } else {
                    pending.addLast(text);
                    if (!sending) {
                        sending = true;
                        start = true;
                    }
                }
            }
            if (overflowed) {
                overflow.run();
            } else if (start) {
                drain();
            }
        }

        private void drain() {
            String next;
            synchronized (lock) {
                if (stopped) {
                    sending = false;
                    return;
                }
                next = pending.pollFirst();
                if (next == null) {
                    sending = false;
                    return;
                }
            }
            CompletionStage<Void> stage;
            try {
                stage = peer.sendTextAsync(next);
            } catch (RuntimeException failure) {
                stop();
                sendFailure.run();
                return;
            }
            if (stage == null) {
                stop();
                sendFailure.run();
                return;
            }
            stage.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    stop();
                    sendFailure.run();
                } else {
                    drain();
                }
            });
        }

        private void stop() {
            synchronized (lock) {
                stopped = true;
                pending.clear();
            }
        }
    }

    private static HostingRouteKind kind(StateValue.ObjectValue frame) {
        return switch (requireString(frame, "kind")) {
            case "agent" -> HostingRouteKind.AGENT;
            case "workflow" -> HostingRouteKind.WORKFLOW;
            default ->
                throw new HostingException(
                        HostingErrorCode.MALFORMED_REQUEST, "WebSocket kind must be agent or workflow.");
        };
    }

    private static String operationId(StateValue.ObjectValue frame) {
        String value = requireString(frame, "operationId");
        if (!OPERATION_ID.matcher(value).matches()) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "WebSocket operationId is invalid.");
        }
        return value;
    }

    private static StateValue.ObjectValue requireObject(StateValue.ObjectValue frame, String name) {
        StateValue value = frame.values().get(name);
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "WebSocket member '" + name + "' must be an object.");
    }

    private static String requireString(StateValue.ObjectValue frame, String name) {
        StateValue value = frame.values().get(name);
        if (value instanceof StateValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "WebSocket member '" + name + "' must be a non-blank string.");
    }

    private static long requirePositiveLong(StateValue.ObjectValue frame, String name) {
        StateValue value = frame.values().get(name);
        if (value instanceof StateValue.NumberValue number
                && number.value().scale() <= 0
                && number.value().signum() > 0) {
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException ignored) {
                // Fall through to the stable error.
            }
        }
        throw new HostingException(
                HostingErrorCode.MALFORMED_REQUEST, "WebSocket member '" + name + "' must be a positive integer.");
    }

    private static void requireVersion(StateValue.ObjectValue frame) {
        if (!HostingJsonCodec.WIRE_VERSION.equals(requireString(frame, "version"))) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Unsupported Java hosting WebSocket version.");
        }
    }

    private static void rejectUnknown(StateValue.ObjectValue frame, Set<String> allowed, String type) {
        frame.values().keySet().stream()
                .filter(name -> !allowed.contains(name))
                .sorted()
                .findFirst()
                .ifPresent(name -> {
                    throw new HostingException(
                            HostingErrorCode.MALFORMED_REQUEST,
                            "WebSocket " + type + " frame contains unknown member.");
                });
    }

    private static HostingError error(Throwable failure) {
        Throwable cause = com.microsoft.agents.core.RunHandles.unwrap(failure);
        return cause instanceof HostingException hosting
                ? hosting.error()
                : HostingError.of(HostingErrorCode.INTERNAL_ERROR, "WebSocket operation failed.");
    }

    private record Operation(
            String operationId,
            HostingRouteKind kind,
            String routeId,
            HostingRun run,
            AtomicReference<Flow.Subscription> subscription,
            AtomicBoolean terminalSent) {
        private Operation(String operationId, HostingRouteKind kind, String routeId, HostingRun run) {
            this(operationId, kind, routeId, run, new AtomicReference<>(), new AtomicBoolean());
        }

        private Operation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(subscription, "subscription");
            Objects.requireNonNull(terminalSent, "terminalSent");
        }
    }
}
