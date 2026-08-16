// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryFoundryHostedSessionStoreTest {
    @Test
    void store_shouldEnforceCasAndCapacityWithoutDeletingRemoteResources() {
        MutableClock clock = new MutableClock();
        FoundryHostedSessionKey first = new FoundryHostedSessionKey("route", "one", "tenant", "conversation");
        FoundryHostedSessionKey second = new FoundryHostedSessionKey("route", "two", "tenant", "conversation");
        try (InMemoryFoundryHostedSessionStore store =
                new InMemoryFoundryHostedSessionStore(1, Duration.ofHours(1), clock)) {
            FoundryHostedSession stored = store.saveAsync(
                            FoundryHostedSession.create(first, "thread-one", clock.instant()),
                            FoundryHostedSession.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();

            assertThatThrownBy(() -> store.saveAsync(stored, FoundryHostedSession.CREATE_ONLY)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.StorageConflictException.class);

            store.saveAsync(
                            FoundryHostedSession.create(second, "thread-two", clock.instant()),
                            FoundryHostedSession.CREATE_ONLY)
                    .toCompletableFuture()
                    .join();

            assertThat(store.size()).isEqualTo(1);
            assertThat(store.loadAsync(first).toCompletableFuture().join()).isEmpty();
            assertThat(store.loadAsync(second).toCompletableFuture().join()).isPresent();
        }
    }

    @Test
    void store_shouldRetainBoundedMessageIdsUntilTtlAndClearThemOnClose() {
        MutableClock clock = new MutableClock();
        FoundryHostedSessionKey key = new FoundryHostedSessionKey("route", "one", "tenant", "conversation");
        InMemoryFoundryHostedSessionStore store =
                new InMemoryFoundryHostedSessionStore(1, Duration.ofMinutes(5), 1, clock);
        FoundryHostedSession created = FoundryHostedSession.create(key, "thread-one", clock.instant());
        FoundryHostedSession withMessage = new FoundryHostedSession(
                key,
                created.threadId(),
                null,
                List.of("message-one"),
                created.revision(),
                created.createdAt(),
                created.updatedAt());
        store.saveAsync(withMessage, FoundryHostedSession.CREATE_ONLY)
                .toCompletableFuture()
                .join();

        assertThat(store.loadAsync(key).toCompletableFuture().join())
                .get()
                .extracting(FoundryHostedSession::submittedMessageIds)
                .isEqualTo(List.of("message-one"));
        FoundryHostedSession oversized = new FoundryHostedSession(
                key,
                withMessage.threadId(),
                null,
                List.of("message-one", "message-two"),
                1,
                withMessage.createdAt(),
                clock.instant());
        assertThatThrownBy(() ->
                        store.saveAsync(oversized, 1).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofMinutes(6));
        assertThat(store.loadAsync(key).toCompletableFuture().join()).isEmpty();

        store.close();
        assertThat(store.size()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-10T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
