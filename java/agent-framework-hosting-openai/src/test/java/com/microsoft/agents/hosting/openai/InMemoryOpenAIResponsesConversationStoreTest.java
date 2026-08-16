// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.VersionedSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryOpenAIResponsesConversationStoreTest {
    @Test
    void activeState_shouldNotExpireBeforeRunReleasesIt() throws Exception {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T00:00:00Z"));
        OpenAIResponsesConversationKey key = new OpenAIResponsesConversationKey(
                "alice", "tenant-a", "agent", OpenAIResponsesReferenceType.CONVERSATION, "conversation");
        try (InMemoryOpenAIResponsesConversationStore store =
                new InMemoryOpenAIResponsesConversationStore(2, Duration.ofSeconds(1), clock)) {
            VersionedSnapshot<OpenAIResponsesConversationState> active = store.compareAndSetAsync(
                            key,
                            new OpenAIResponsesConversationState(List.of(), "request-1", clock.instant()),
                            OpenAIResponsesConversationStore.CREATE_ONLY)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            // Act
            clock.advance(Duration.ofSeconds(2));
            int activeSize = store.size();
            store.compareAndSetAsync(
                            key,
                            OpenAIResponsesConversationState.inactive(List.of(), clock.instant()),
                            active.revision())
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            clock.advance(Duration.ofSeconds(2));
            int inactiveSize = store.size();

            // Assert
            assertThat(activeSize).isEqualTo(1);
            assertThat(inactiveSize).isZero();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock supports UTC only.");
            }
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
