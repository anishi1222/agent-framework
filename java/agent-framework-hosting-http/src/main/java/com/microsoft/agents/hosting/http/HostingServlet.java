// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingError;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingEvent;
import com.microsoft.agents.hosting.HostingEventType;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRun;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class HostingServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    private final transient HostingHttpHandler handler;

    private final transient Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();

    private final transient Set<SseSubscriber> streams = ConcurrentHashMap.newKeySet();

    private final transient AtomicBoolean shuttingDown = new AtomicBoolean();

    HostingServlet(HostingHttpHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (shuttingDown.get()) {
            writeError(
                    response, HostingError.of(HostingErrorCode.TOO_MANY_REQUESTS, "Hosting server is shutting down."));
            return;
        }
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        HostingHttpRequest hostingRequest;
        try {
            hostingRequest = request(request, readBounded(request), cancellation);
        } catch (HostingException failure) {
            writeError(response, failure.error());
            return;
        }
        AsyncContext async = request.startAsync();
        async.setTimeout(handler.options().limits().runTimeout().plusSeconds(5).toMillis());
        Exchange exchange = new Exchange(async, cancellation);
        exchanges.add(exchange);
        async.addListener(exchange);
        handler.handleAsync(hostingRequest)
                .whenComplete((result, failure) -> Thread.startVirtualThread(() -> {
                    if (failure != null) {
                        writeFailure(response, exchange, failure);
                    } else if (result.isStreaming()) {
                        startSse(response, exchange, result);
                    } else {
                        writeFinite(response, exchange, result);
                    }
                }));
    }

    void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(streams).forEach(SseSubscriber::close);
        List.copyOf(exchanges).forEach(Exchange::cancel);
        long deadline =
                System.nanoTime() + handler.options().gracefulShutdownTimeout().toNanos();
        while (!exchanges.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    int activeExchangeCount() {
        return exchanges.size();
    }

    private void startSse(HttpServletResponse response, Exchange exchange, HostingHttpResponse result) {
        exchange.attachResponse(result);
        try {
            apply(response, result);
            response.flushBuffer();
            SseSubscriber subscriber = new SseSubscriber(result, response.getOutputStream(), exchange);
            streams.add(subscriber);
            subscriber.start();
        } catch (IOException | RuntimeException failure) {
            exchange.cancel();
        }
    }

    private void writeFinite(HttpServletResponse response, Exchange exchange, HostingHttpResponse result) {
        exchange.attachResponse(result);
        try {
            apply(response, result);
            byte[] body = result.body();
            if (body.length > 0) {
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
            response.flushBuffer();
        } catch (IOException | RuntimeException ignored) {
            exchange.cancel();
            return;
        }
        result.confirmDelivery();
        exchange.complete();
    }

    private void writeFailure(HttpServletResponse response, Exchange exchange, Throwable failure) {
        Throwable cause = RunHandles.unwrap(failure);
        HostingError error = cause instanceof HostingException hosting
                ? hosting.error()
                : HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosting request failed.");
        try {
            writeError(response, error);
        } catch (IOException | RuntimeException ignored) {
            exchange.cancel();
            return;
        }
        exchange.complete();
    }

    private void writeError(HttpServletResponse response, HostingError error) throws IOException {
        byte[] body = handler.encodeError(error);
        response.setStatus(error.code().httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    private static void apply(HttpServletResponse response, HostingHttpResponse result) {
        response.setStatus(result.status());
        result.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    private byte[] readBounded(HttpServletRequest request) {
        long maximum = handler.options().limits().maxRequestBytes();
        long contentLength = request.getContentLengthLong();
        if (contentLength > maximum) {
            throw new HostingException(
                    HostingErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds maxRequestBytes " + maximum + ".");
        }
        int readLimit = (int) Math.min(Integer.MAX_VALUE - 1L, maximum + 1L);
        try {
            byte[] body = request.getInputStream().readNBytes(readLimit);
            if (body.length > maximum) {
                throw new HostingException(
                        HostingErrorCode.PAYLOAD_TOO_LARGE, "Request exceeds maxRequestBytes " + maximum + ".");
            }
            return body;
        } catch (IOException exception) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Unable to read request body.", exception);
        }
    }

    private static HostingHttpRequest request(
            HttpServletRequest request, byte[] body, DefaultRunCancellation cancellation) {
        LinkedHashMap<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        URI uri = URI.create(
                request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        return new HostingHttpRequest(
                request.getMethod(),
                uri,
                new InetSocketAddress(request.getRemoteAddr(), request.getRemotePort()),
                headers,
                body,
                cancellation);
    }

    private final class Exchange implements AsyncListener {
        private final AsyncContext async;

        private final DefaultRunCancellation cancellation;

        private final AtomicReference<ExchangeState> state = new AtomicReference<>(ExchangeState.OPEN);

        private final AtomicReference<HostingHttpResponse> response = new AtomicReference<>();

        private Exchange(AsyncContext async, DefaultRunCancellation cancellation) {
            this.async = async;
            this.cancellation = cancellation;
        }

        private void attachResponse(HostingHttpResponse value) {
            Objects.requireNonNull(value, "value");
            if (!response.compareAndSet(null, value)) {
                throw new IllegalStateException("A hosting response is already attached to this exchange.");
            }
            if (state.get() != ExchangeState.OPEN) {
                value.discardUndeliveredOutcome();
            }
        }

        private void complete() {
            finish(ExchangeState.COMPLETED);
        }

        private void cancel() {
            finish(ExchangeState.CANCELLED);
        }

        private void finish(ExchangeState completedState) {
            if (!state.compareAndSet(ExchangeState.OPEN, completedState)) {
                return;
            }
            if (completedState == ExchangeState.CANCELLED) {
                cancellation.cancel();
            }
            HostingHttpResponse current = response.get();
            if (current != null) {
                current.discardUndeliveredOutcome();
            }
            exchanges.remove(this);
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
                // The container already completed this exchange.
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            cancel();
        }

        @Override
        public void onError(AsyncEvent event) {
            cancel();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {}
    }

    private final class SseSubscriber implements Flow.Subscriber<HostingEvent> {
        private final HostingHttpResponse response;

        private final HostingRun run;

        private final OutputStream output;

        private final Exchange exchange;

        private final BlockingQueue<SseItem> pending;

        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        private final AtomicReference<HostingOutcome> terminal = new AtomicReference<>();

        private final AtomicBoolean sourceComplete = new AtomicBoolean();

        private final AtomicBoolean terminalQueued = new AtomicBoolean();

        private final AtomicBoolean closed = new AtomicBoolean();

        private final AtomicInteger bufferedEvents = new AtomicInteger();

        private final AtomicLong transportSequence = new AtomicLong();

        private volatile long lastWriteNanos = System.nanoTime();

        private Thread writer;

        private Thread idle;

        private SseSubscriber(HostingHttpResponse response, OutputStream output, Exchange exchange) {
            this.response = Objects.requireNonNull(response, "response");
            this.run = Objects.requireNonNull(response.streamingRun(), "streamingRun");
            this.output = Objects.requireNonNull(output, "output");
            this.exchange = Objects.requireNonNull(exchange, "exchange");
            pending = new ArrayBlockingQueue<>(
                    Math.addExact(handler.options().limits().maxSseBufferedEvents(), 1));
        }

        private void start() {
            writer = Thread.startVirtualThread(this::writeLoop);
            idle = Thread.startVirtualThread(this::idleLoop);
            run.terminalAsync().whenComplete((outcome, failure) -> {
                HostingOutcome value = outcome;
                if (value == null) {
                    value = HostingOutcome.failed(
                            run.runId(), HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosted SSE run failed."));
                }
                terminal.set(value);
                tryFinish();
            });
            run.events().subscribe(this);
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (!subscription.compareAndSet(null, value)) {
                value.cancel();
                return;
            }
            value.request(1);
        }

        @Override
        public void onNext(HostingEvent item) {
            if (closed.get() || terminalQueued.get()) {
                return;
            }
            int buffered = bufferedEvents.incrementAndGet();
            if (buffered > handler.options().limits().maxSseBufferedEvents() || !pending.offer(new EventItem(item))) {
                bufferedEvents.decrementAndGet();
                failOverflow();
                run.cancel();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            terminal.compareAndSet(
                    null,
                    HostingOutcome.failed(
                            run.runId(),
                            HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosted SSE stream failed.")));
            sourceComplete.set(true);
            tryFinish();
        }

        @Override
        public void onComplete() {
            sourceComplete.set(true);
            tryFinish();
        }

        private void tryFinish() {
            HostingOutcome outcome = terminal.get();
            if (outcome != null && sourceComplete.get() && terminalQueued.compareAndSet(false, true)) {
                if (!pending.offer(new TerminalItem(outcome))) {
                    failOverflow();
                    run.cancel();
                }
            }
        }

        private void failOverflow() {
            pending.clear();
            bufferedEvents.set(0);
            terminalQueued.set(true);
            pending.offer(new TerminalItem(HostingOutcome.overflow(
                    run.runId(), HostingError.of(HostingErrorCode.OVERFLOW, "SSE output buffer overflowed."))));
        }

        private void writeLoop() {
            try {
                writeStart();
                while (!closed.get()) {
                    SseItem item = pending.take();
                    switch (item) {
                        case EventItem event -> {
                            writeFrame(
                                    event.event().type().value(),
                                    handler.codec().encodeEvent(event.event()));
                            bufferedEvents.updateAndGet(current -> Math.max(0, current - 1));
                            Flow.Subscription current = subscription.get();
                            if (current != null) {
                                current.request(1);
                            }
                        }
                        case TerminalItem terminalItem -> {
                            writeFrame("terminal", terminalBytes(terminalItem.outcome()));
                            finishDelivered();
                            return;
                        }
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                finishUndelivered();
            } catch (IOException | RuntimeException failure) {
                finishUndelivered();
            }
        }

        private byte[] terminalBytes(HostingOutcome outcome) {
            try {
                return handler.encodeOutcome(outcome, response);
            } catch (RuntimeException failure) {
                response.confirmDelivery();
                return handler.encodeOutcome(HostingOutcome.overflow(
                        run.runId(),
                        HostingError.of(HostingErrorCode.OVERFLOW, "SSE terminal outcome exceeded transport limits.")));
            }
        }

        private void writeStart() throws IOException {
            StateValue value = StateValue.object(Map.of(
                    "version",
                    StateValue.string(HostingJsonCodec.WIRE_VERSION),
                    "type",
                    StateValue.string("event"),
                    "event",
                    StateValue.string(HostingEventType.RUN_STARTED.value()),
                    "runId",
                    StateValue.string(run.runId()),
                    "createdAt",
                    StateValue.string(Instant.now().toString()),
                    "data",
                    StateValue.object(Map.of())));
            writeFrame("run-started", handler.codec().encodeValue(value));
        }

        private void writeFrame(String event, byte[] data) throws IOException {
            long id = transportSequence.getAndIncrement();
            output.write(("id: " + id + "\n").getBytes(StandardCharsets.UTF_8));
            output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            output.write("data: ".getBytes(StandardCharsets.UTF_8));
            output.write(data);
            output.write("\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            lastWriteNanos = System.nanoTime();
        }

        private void idleLoop() {
            long sleep = Math.max(100L, handler.options().limits().idleTimeout().toMillis() / 2);
            while (!closed.get()) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (System.nanoTime() - lastWriteNanos
                        >= handler.options().limits().idleTimeout().toNanos()) {
                    run.cancel();
                    return;
                }
            }
        }

        private void finishDelivered() {
            response.confirmDelivery();
            finish(false);
        }

        private void finishUndelivered() {
            finish(true);
        }

        private void finish(boolean cancel) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            streams.remove(this);
            Flow.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
            Thread idleThread = idle;
            if (idleThread != null) {
                idleThread.interrupt();
            }
            if (cancel) {
                exchange.cancel();
            } else {
                exchange.complete();
            }
        }

        private void close() {
            finishUndelivered();
            Thread writerThread = writer;
            if (writerThread != null) {
                writerThread.interrupt();
            }
        }
    }

    private sealed interface SseItem permits EventItem, TerminalItem {}

    private record EventItem(HostingEvent event) implements SseItem {
        private EventItem {
            Objects.requireNonNull(event, "event");
        }
    }

    private record TerminalItem(HostingOutcome outcome) implements SseItem {
        private TerminalItem {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private enum ExchangeState {
        OPEN,
        COMPLETED,
        CANCELLED
    }
}
