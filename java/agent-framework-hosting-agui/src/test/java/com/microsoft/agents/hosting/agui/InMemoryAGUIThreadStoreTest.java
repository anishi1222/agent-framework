// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.protocols.agui.AGUIMessages;
import com.microsoft.agents.protocols.agui.AGUIProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryAGUIThreadStoreTest {
    @Test
    void store_shouldEnforcePrincipalIsolationCasCapacityAndTtl() {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        AGUIThreadKey first = new AGUIThreadKey("alice", "tenant-a", HostingRouteKind.AGENT, "agent", "shared");
        AGUIThreadKey second = new AGUIThreadKey("bob", "tenant-b", HostingRouteKind.AGENT, "agent", "shared");
        AGUIThreadState state = state(clock.instant());

        try (InMemoryAGUIThreadStore store = new InMemoryAGUIThreadStore(2, Duration.ofMinutes(5), clock)) {
            // Act
            var firstStored = store.compareAndSetAsync(first, state, 0)
                    .toCompletableFuture()
                    .join();
            var secondStored = store.compareAndSetAsync(second, state, 0)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(firstStored.revision()).isNotEqualTo(secondStored.revision());
            assertThat(store.loadAsync(first).toCompletableFuture().join()).isPresent();
            assertThat(store.loadAsync(second).toCompletableFuture().join()).isPresent();
            AGUIThreadKey third = new AGUIThreadKey("carol", "tenant-c", HostingRouteKind.AGENT, "agent", "shared");
            assertThatThrownBy(() -> store.compareAndSetAsync(third, state, 0)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(AGUIProtocolException.class);
            assertThatThrownBy(() -> store.compareAndSetAsync(first, state, 0)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(StorageConflictException.class);

            clock.advance(Duration.ofMinutes(6));
            assertThat(store.loadAsync(first).toCompletableFuture().join()).isEmpty();
            assertThat(store.loadAsync(second).toCompletableFuture().join()).isEmpty();
            assertThat(store.size()).isZero();
        }
    }

    private static AGUIThreadState state(Instant now) {
        return AGUIThreadState.initial(
                List.of(new AGUIMessages.User("user", new AGUIMessages.TextUserContent("hello"), null, null)),
                StateValue.object(Map.of()),
                now);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

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
            return current;
        }
    }
}
