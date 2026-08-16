// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedBodyHandlersTest {
    @Test
    void subscriber_shouldCollectBytesWithinLimit() {
        HttpResponse.BodySubscriber<byte[]> subscriber = BoundedBodyHandlers.byteArray(
                        4, () -> new IllegalStateException("overflow"))
                .apply(responseInfo());
        subscriber.onSubscribe(new RecordingSubscription());

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void subscriber_shouldCancelBeforeBufferingBeyondLimit() {
        HttpResponse.BodySubscriber<byte[]> subscriber = BoundedBodyHandlers.byteArray(
                        3, () -> new IllegalStateException("overflow"))
                .apply(responseInfo());
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));

        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("overflow");
    }

    private static HttpResponse.ResponseInfo responseInfo() {
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_2;
            }
        };
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
