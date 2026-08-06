// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import java.util.Objects;
import java.util.concurrent.Flow;

final class MiddlewarePublishers {
    private MiddlewarePublishers() {}

    static <T> Flow.Publisher<T> failed(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean signalled;

                @Override
                public synchronized void request(long count) {
                    if (signalled) {
                        return;
                    }
                    signalled = true;
                    subscriber.onError(failure);
                }

                @Override
                public synchronized void cancel() {
                    signalled = true;
                }
            });
        };
    }
}
