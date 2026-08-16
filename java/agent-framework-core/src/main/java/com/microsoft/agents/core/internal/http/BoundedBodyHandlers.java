// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

/**
 * Provides provider-neutral JDK HTTP body handlers that enforce response limits while bytes are
 * received.
 *
 * <p>This public type is an internal cross-module transport utility, not an application extension
 * point.
 */
public final class BoundedBodyHandlers {
    private BoundedBodyHandlers() {}

    /**
     * Creates a byte-array body handler that cancels the subscription before it exceeds a limit.
     *
     * @param maximumBytes positive response-byte limit
     * @param overflowFailure creates the sanitized failure reported on overflow
     * @return bounded body handler
     */
    public static HttpResponse.BodyHandler<byte[]> byteArray(
            int maximumBytes, Supplier<? extends RuntimeException> overflowFailure) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive.");
        }
        Objects.requireNonNull(overflowFailure, "overflowFailure");
        return responseInfo -> new BoundedByteArraySubscriber(maximumBytes, overflowFailure);
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final Supplier<? extends RuntimeException> overflowFailure;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean done;

        private BoundedByteArraySubscriber(int maximumBytes, Supplier<? extends RuntimeException> overflowFailure) {
            this.maximumBytes = maximumBytes;
            this.overflowFailure = overflowFailure;
            output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.minimalCompletionStage();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = Objects.requireNonNull(value, "subscription");
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            long batchBytes = items.stream().mapToLong(ByteBuffer::remaining).sum();
            if ((long) output.size() + batchBytes > maximumBytes) {
                done = true;
                subscription.cancel();
                body.completeExceptionally(overflowFailure.get());
                return;
            }
            for (ByteBuffer item : items) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            if (!done) {
                done = true;
                body.completeExceptionally(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                body.complete(output.toByteArray());
            }
        }
    }
}
