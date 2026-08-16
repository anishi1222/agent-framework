// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends strict AG-UI requests with the JDK HTTP client and consumes incremental SSE with bounded
 * backpressure.
 *
 * <p>Redirects are always disabled. HTTPS is required unless loopback HTTP is explicitly enabled.
 * Cancellation closes the response body and interrupts its virtual reader thread.
 *
 * <p>At end of stream, the client first dispatches any syntactically complete SSE data frame that
 * was not followed by the official blank-line delimiter, then normalizes and validates that event
 * in ordinary wire order, and only then requires exactly one balanced terminal event. A truncated
 * frame is an error; EOF never implies successful run completion.
 */
public final class AGUIClient implements AutoCloseable {
    private static final String CAPABILITY_MEDIA_TYPE =
            "application/vnd.microsoft.agent-framework.agui-capabilities+json";

    private final AGUIClientOptions options;

    private final AGUIJsonCodec codec;

    private final ExecutorService executor;

    private final ScheduledThreadPoolExecutor scheduler;

    private final HttpClient httpClient;

    private final Set<RunSubscription> active = ConcurrentHashMap.newKeySet();

    private final AtomicReference<AGUIClientCapabilities> capabilities = new AtomicReference<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a client from secure immutable options.
     *
     * @param options client options
     */
    public AGUIClient(AGUIClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        codec = new AGUIJsonCodec(options.limits());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("agent-framework-agui-client-timeouts")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(executor)
                .build();
    }

    /**
     * Queries and caches the framework-extension capability document.
     *
     * @return capability stage
     */
    public CompletionStage<AGUIClientCapabilities> capabilitiesAsync() {
        requireOpen();
        HttpRequest.Builder request = HttpRequest.newBuilder(options.capabilitiesEndpoint())
                .timeout(options.connectTimeout())
                .header("Accept", CAPABILITY_MEDIA_TYPE)
                .GET();
        addHeaders(request);
        CompletableFuture<AGUIClientCapabilities> result = new CompletableFuture<>();
        AtomicReference<InputStream> body = new AtomicReference<>();
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> {
                    close(body.getAndSet(null));
                    result.completeExceptionally(new AGUIProtocolException(
                            AGUIErrorCode.TIMEOUT, "AG-UI capability request exceeded requestTimeout."));
                },
                options.requestTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
        httpClient
                .sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(sanitize(failure));
                        return;
                    }
                    if (result.isDone()) {
                        close(response.body());
                        return;
                    }
                    body.set(response.body());
                    if (result.isDone()) {
                        close(body.getAndSet(null));
                        return;
                    }
                    executor.execute(() -> {
                        try {
                            AGUIClientCapabilities value = decodeCapabilities(response);
                            capabilities.set(value);
                            result.complete(value);
                        } catch (Throwable decodeFailure) {
                            result.completeExceptionally(sanitize(decodeFailure));
                        }
                    });
                });
        result.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            close(body.getAndSet(null));
        });
        return result.minimalCompletionStage();
    }

    /**
     * Starts a cold single-subscriber AG-UI run stream.
     *
     * @param input exact run input
     * @return validated event publisher
     */
    public Flow.Publisher<AGUIEvent> runStreaming(RunAgentInput input) {
        requireOpen();
        Objects.requireNonNull(input, "input");
        return new RunPublisher(input);
    }

    /**
     * Starts a resume only after the server capability document declared opaque resume support.
     *
     * @param input run input with non-empty resume entries
     * @return validated event publisher
     */
    public Flow.Publisher<AGUIEvent> resumeStreaming(RunAgentInput input) {
        requireOpen();
        Objects.requireNonNull(input, "input");
        AGUIClientCapabilities declared = capabilities.get();
        if (declared == null || !declared.resumeSupported()) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.SECURITY,
                    "Resume requires a previously loaded server capability that declares support.");
        }
        if (input.resume().isEmpty()) {
            throw new AGUIProtocolException(
                    AGUIErrorCode.INVALID_MODEL, "Resume input requires at least one resume entry.");
        }
        return new RunPublisher(input);
    }

    /**
     * Collects a finite validated run stream.
     *
     * @param input exact run input
     * @return immutable events
     */
    public CompletionStage<List<AGUIEvent>> runAsync(RunAgentInput input) {
        CompletableFuture<List<AGUIEvent>> result = new CompletableFuture<>();
        ArrayList<AGUIEvent> events = new ArrayList<>();
        runStreaming(input).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AGUIEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(events));
            }
        });
        return result.minimalCompletionStage();
    }

    /** Cancels active requests and releases client-owned executors. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(active).forEach(RunSubscription::cancel);
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private AGUIClientCapabilities decodeCapabilities(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw transport("AG-UI capability endpoint returned an unexpected HTTP status.");
            }
            requireMediaType(response, CAPABILITY_MEDIA_TYPE);
            StateValue value =
                    codec.decodeValue(readBounded(body, options.limits().maxRequestBytes()));
            if (!(value instanceof StateValue.ObjectValue object)) {
                throw transport("AG-UI capability response must be an object.");
            }
            requireString(object, "protocol", "ag-ui");
            String schemaVersion = string(object, "schemaVersion");
            StateValue.ObjectValue transport = object(object, "transport");
            StateValue.ObjectValue resume = object(object, "resume");
            return new AGUIClientCapabilities(
                    schemaVersion,
                    bool(transport, "sse"),
                    bool(resume, "supported"),
                    bool(resume, "processLocal"),
                    bool(resume, "oneTime"));
        } catch (IOException exception) {
            throw transport("Unable to read AG-UI capability response.", exception);
        }
    }

    private void addHeaders(HttpRequest.Builder request) {
        options.headers().forEach(request::header);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new AGUIProtocolException(AGUIErrorCode.CANCELLED, "AG-UI client is closed.");
        }
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if ((long) output.size() + read > maximum) {
                throw new AGUIProtocolException(AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI response exceeds its byte limit.");
            }

            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void close(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Best effort after timeout or terminal decode.
        }
    }

    private static void requireMediaType(HttpResponse<?> response, String expected) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        String mediaType = contentType.split(";", 2)[0].trim();
        if (!expected.equalsIgnoreCase(mediaType)) {
            throw transport("AG-UI endpoint returned an unexpected media type.");
        }
        String contentEncoding =
                response.headers().firstValue("content-encoding").orElse("identity");
        if (!"identity".equalsIgnoreCase(contentEncoding)) {
            throw transport("AG-UI endpoint returned an unsupported content encoding.");
        }
    }

    private static String string(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string && !string.value().isBlank()) {
            return string.value();
        }
        throw transport("AG-UI capability response is malformed.");
    }

    private static void requireString(StateValue.ObjectValue object, String name, String expected) {
        if (!expected.equals(string(object, name))) {
            throw transport("AG-UI capability response is malformed.");
        }
    }

    private static StateValue.ObjectValue object(StateValue.ObjectValue parent, String name) {
        StateValue value = parent.values().get(name);
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw transport("AG-UI capability response is malformed.");
    }

    private static boolean bool(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.BooleanValue bool) {
            return bool.value();
        }
        throw transport("AG-UI capability response is malformed.");
    }

    private static AGUIProtocolException transport(String message) {
        return new AGUIProtocolException(AGUIErrorCode.TRANSPORT, message);
    }

    private static AGUIProtocolException transport(String message, Throwable cause) {
        return new AGUIProtocolException(AGUIErrorCode.TRANSPORT, message, cause);
    }

    private final class RunPublisher implements Flow.Publisher<AGUIEvent> {
        private final RunAgentInput input;

        private final AtomicBoolean subscribed = new AtomicBoolean();

        private RunPublisher(RunAgentInput input) {
            this.input = input;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super AGUIEvent> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onError(new IllegalStateException("AG-UI run publishers support one subscriber."));
                return;
            }
            RunSubscription subscription = new RunSubscription(subscriber, input);
            active.add(subscription);
            subscriber.onSubscribe(subscription);
        }
    }

    private final class RunSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super AGUIEvent> subscriber;

        private final RunAgentInput input;

        private final Object demandLock = new Object();

        private final AtomicLong demand = new AtomicLong();

        private final AtomicBoolean started = new AtomicBoolean();

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> idleDeadline = new AtomicReference<>();

        private volatile Thread worker;

        private RunSubscription(Flow.Subscriber<? super AGUIEvent> subscriber, RunAgentInput input) {
            this.subscriber = subscriber;
            this.input = input;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Reactive Streams demand must be positive."));
                return;
            }
            addDemand(count);
            synchronized (demandLock) {
                demandLock.notifyAll();
            }
            if (started.compareAndSet(false, true)) {
                executor.execute(this::run);
            }
        }

        @Override
        public void cancel() {
            synchronized (demandLock) {
                if (!cancelled.compareAndSet(false, true)) {
                    return;
                }
                terminated.compareAndSet(false, true);
                demandLock.notifyAll();
            }
            closeBody();
            ScheduledFuture<?> idle = idleDeadline.getAndSet(null);
            if (idle != null) {
                idle.cancel(false);
            }
            Thread running = worker;
            if (running != null) {
                running.interrupt();
            }
            active.remove(this);
        }

        private void run() {
            worker = Thread.currentThread();
            ScheduledFuture<?> overall = scheduler.schedule(
                    () -> fail(new AGUIProtocolException(AGUIErrorCode.TIMEOUT, "AG-UI run exceeded requestTimeout.")),
                    options.requestTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(options.endpoint())
                        .timeout(options.connectTimeout())
                        .header("Content-Type", AGUIProtocol.JSON_MEDIA_TYPE)
                        .header("Accept", AGUIProtocol.SSE_MEDIA_TYPE);
                addHeaders(request);
                request.POST(HttpRequest.BodyPublishers.ofByteArray(codec.encodeRunAgentInput(input)));
                HttpResponse<InputStream> response =
                        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw transport("AG-UI endpoint returned an unexpected HTTP status.");
                }
                requireMediaType(response, AGUIProtocol.SSE_MEDIA_TYPE);
                responseBody.set(response.body());
                resetIdleDeadline();
                consume(response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) {
                    fail(new AGUIProtocolException(AGUIErrorCode.CANCELLED, "AG-UI run was interrupted.", exception));
                }
            } catch (Throwable failure) {
                fail(sanitize(failure));
            } finally {
                overall.cancel(false);
                closeBody();
                active.remove(this);
            }
        }

        private void consume(InputStream body) throws IOException, InterruptedException {
            var decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (PushbackReader reader = new PushbackReader(new InputStreamReader(body, decoder), 1)) {
                AGUISseParser parser = new AGUISseParser(codec);
                AGUIEventNormalizer normalizer = new AGUIEventNormalizer();
                AGUIEventStreamValidator validator =
                        new AGUIEventStreamValidator(options.limits(), AGUIValidationContext.fromInput(input));
                ArrayDeque<AGUIEvent> pending = new ArrayDeque<>();
                long totalBytes = 0;
                boolean eof = false;
                while (!eof || !pending.isEmpty()) {
                    if (!pending.isEmpty()) {
                        awaitDemand();
                        if (cancelled.get()) {
                            return;
                        }
                        AGUIEvent event = pending.removeFirst();
                        synchronized (demandLock) {
                            if (cancelled.get()) {
                                return;
                            }
                            subscriber.onNext(event);
                            decrementDemand();
                        }
                        continue;
                    }
                    String line = readLine(reader, options.limits().maxSseFrameBytes());
                    if (line == null) {
                        eof = true;
                        for (AGUIEvent parsed : parser.finish()) {
                            enqueue(normalizer.accept(parsed), validator, pending);
                        }
                        enqueue(normalizer.finish(), validator, pending);
                        validator.finish();
                        continue;
                    }
                    resetIdleDeadline();
                    totalBytes = Math.addExact(totalBytes, line.getBytes(StandardCharsets.UTF_8).length + 1L);
                    if (totalBytes > options.limits().maxResponseBytes()) {
                        throw new AGUIProtocolException(
                                AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI SSE response exceeds maxResponseBytes.");
                    }
                    for (AGUIEvent parsed : parser.acceptLine(line)) {
                        enqueue(normalizer.accept(parsed), validator, pending);
                    }
                }
                complete();
            }
        }

        private void enqueue(
                List<AGUIEvent> events, AGUIEventStreamValidator validator, ArrayDeque<AGUIEvent> pending) {
            for (AGUIEvent event : events) {
                validator.accept(event);
                if (pending.size() >= options.limits().maxBufferedEvents()) {
                    throw new AGUIProtocolException(AGUIErrorCode.OVERFLOW, "AG-UI client event buffer overflowed.");
                }
                pending.addLast(event);
            }
        }

        private void awaitDemand() throws InterruptedException {
            synchronized (demandLock) {
                while (demand.get() == 0 && !cancelled.get()) {
                    demandLock.wait();
                }
            }
        }

        private void addDemand(long count) {
            demand.getAndUpdate(current -> {
                if (current == Long.MAX_VALUE || count == Long.MAX_VALUE) {
                    return Long.MAX_VALUE;
                }
                long added = current + count;
                return added < 0 ? Long.MAX_VALUE : added;
            });
        }

        private void decrementDemand() {
            demand.getAndUpdate(current -> current == Long.MAX_VALUE ? Long.MAX_VALUE : current - 1);
        }

        private void resetIdleDeadline() {
            ScheduledFuture<?> replacement = scheduler.schedule(
                    () -> fail(
                            new AGUIProtocolException(AGUIErrorCode.TIMEOUT, "AG-UI SSE stream exceeded idleTimeout.")),
                    options.idleTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = idleDeadline.getAndSet(replacement);
            if (previous != null) {
                previous.cancel(false);
            }
        }

        private void complete() {
            synchronized (demandLock) {
                if (!cancelled.get() && terminated.compareAndSet(false, true)) {
                    ScheduledFuture<?> idle = idleDeadline.getAndSet(null);
                    if (idle != null) {
                        idle.cancel(false);
                    }
                    subscriber.onComplete();
                }
            }
        }

        private void fail(Throwable failure) {
            synchronized (demandLock) {
                if (cancelled.get() || !terminated.compareAndSet(false, true)) {
                    return;
                }
                cancelled.set(true);
                subscriber.onError(sanitize(failure));
                demandLock.notifyAll();
            }
            closeBody();
            Thread running = worker;
            if (running != null && running != Thread.currentThread()) {
                running.interrupt();
            }
            active.remove(this);
        }

        private void closeBody() {
            InputStream body = responseBody.getAndSet(null);
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Best effort after cancellation or terminal delivery.
                }
            }
        }
    }

    private static String readLine(PushbackReader reader, int maximumCharacters) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int read = reader.read();
            if (read < 0) {
                return line.isEmpty() ? null : line.toString();
            }
            if (read == '\n') {
                return line.toString();
            }
            if (read == '\r') {
                int next = reader.read();
                if (next >= 0 && next != '\n') {
                    reader.unread(next);
                }
                return line.toString();
            }
            if (line.length() >= maximumCharacters) {
                throw new AGUIProtocolException(
                        AGUIErrorCode.LIMIT_EXCEEDED, "AG-UI SSE line exceeds maxSseFrameBytes.");
            }
            line.append((char) read);
        }
    }

    private static Throwable sanitize(Throwable failure) {
        if (failure instanceof AGUIProtocolException || failure instanceof IllegalArgumentException) {
            return failure;
        }
        if (failure instanceof IOException) {
            return transport("AG-UI HTTP/SSE transport failed.", failure);
        }
        return transport("AG-UI client operation failed.", failure);
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
