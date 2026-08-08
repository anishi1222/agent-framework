// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class A2AStreamingPublisher implements Flow.Publisher<A2AStreamEvent> {
    private final Supplier<CompletableFuture<HttpResponse<InputStream>>> starter;
    private final Consumer<HttpResponse<InputStream>> responseValidator;
    private final Function<byte[], A2AStreamEvent> decoder;
    private final A2AEventValidator eventValidator;
    private final RunCancellation cancellation;
    private final A2ALimits limits;
    private final BooleanSupplier permitAcquirer;
    private final Runnable permitReleaser;
    private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> responseFuture =
            new AtomicReference<>();
    private final AtomicReference<InputStream> responseBody = new AtomicReference<>();
    private final AtomicBoolean permitHeld = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final SingleSubscriberPublisher<A2AStreamEvent> publisher;
    private final AtomicReference<RunCancellationRegistration> cancellationRegistration =
            new AtomicReference<>(() -> {});

    A2AStreamingPublisher(
            Supplier<CompletableFuture<HttpResponse<InputStream>>> starter,
            Consumer<HttpResponse<InputStream>> responseValidator,
            Function<byte[], A2AStreamEvent> decoder,
            A2AEventValidator eventValidator,
            RunCancellation cancellation,
            A2ALimits limits,
            BooleanSupplier permitAcquirer,
            Runnable permitReleaser) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.responseValidator = Objects.requireNonNull(responseValidator, "responseValidator");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.eventValidator = Objects.requireNonNull(eventValidator, "eventValidator");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.permitAcquirer = Objects.requireNonNull(permitAcquirer, "permitAcquirer");
        this.permitReleaser = Objects.requireNonNull(permitReleaser, "permitReleaser");
        publisher = new SingleSubscriberPublisher<>(
                this::start,
                this::cancel,
                limits.maxBufferedEvents(),
                ignored -> new A2ATransportException(
                        "A2A stream exceeded maxBufferedEvents=" + limits.maxBufferedEvents() + "."));
        cancellationRegistration.set(RunCancellations.register(cancellation, this::cancel));
    }

    @Override
    public void subscribe(Flow.Subscriber<? super A2AStreamEvent> subscriber) {
        publisher.subscribe(subscriber);
    }

    private void start() {
        if (cancellation.isCancellationRequested()) {
            publisher.fail(new RunCancelledException());
            finish();
            return;
        }
        if (!permitAcquirer.getAsBoolean()) {
            publisher.fail(new A2ATransportException("A2A client concurrent-request limit is exhausted."));
            finish();
            return;
        }
        permitHeld.set(true);
        CompletableFuture<HttpResponse<InputStream>> future;
        try {
            future = starter.get();
        } catch (RuntimeException failure) {
            publisher.fail(failure);
            finish();
            return;
        }
        responseFuture.set(future);
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                if (!cancellation.isCancellationRequested()) {
                    publisher.fail(unwrap(failure));
                }
                finish();
                return;
            }
            try {
                responseValidator.accept(response);
                responseBody.set(response.body());
                Thread.startVirtualThread(this::readEvents);
            } catch (RuntimeException validationFailure) {
                close(response.body());
                publisher.fail(validationFailure);
                finish();
            }
        });
    }

    private void readEvents() {
        try (InputStream input = responseBody.get()) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int eventBytes = 0;
            String line;
            while ((line = readLine(input)) != null) {
                eventBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (eventBytes > limits.maxEventBytes()) {
                    throw new A2ATransportException("A2A SSE event exceeds maxEventBytes.");
                }
                if (line.isEmpty()) {
                    dispatch(data);
                    data.reset();
                    eventBytes = 0;
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if ("data".equals(field)) {
                    if (data.size() > 0) {
                        data.write('\n');
                    }
                    data.writeBytes(value.getBytes(StandardCharsets.UTF_8));
                } else if (!"event".equals(field) && !"id".equals(field) && !"retry".equals(field)) {
                    throw new A2ATransportException("A2A SSE event contains unsupported field '" + field + "'.");
                }
            }
            dispatch(data);
            eventValidator.verifyComplete();
            publisher.complete(this::finish);
        } catch (Throwable failure) {
            if (!cancellation.isCancellationRequested()) {
                publisher.fail(failure);
            }
            finish();
        }
    }

    private void dispatch(ByteArrayOutputStream data) {
        if (data.size() == 0) {
            return;
        }
        A2AStreamEvent event = decoder.apply(data.toByteArray());
        eventValidator.accept(event);
        publisher.emit(event);
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            line.write(value);
            if (line.size() > limits.maxEventBytes()) {
                throw new A2ATransportException("A2A SSE line exceeds maxEventBytes.");
            }
        }
    }

    private void cancel() {
        cancellation.cancel();
        CompletableFuture<?> future = responseFuture.get();
        if (future != null) {
            future.cancel(true);
        }
        close(responseBody.get());
        finish();
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        cancellationRegistration.getAndSet(() -> {}).close();
        if (permitHeld.compareAndSet(true, false)) {
            permitReleaser.run();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static void close(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Stream cancellation is best effort after the HTTP future is canceled.
        }
    }
}
