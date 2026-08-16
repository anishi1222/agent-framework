// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingContinuationRegistryTest {
    @Test
    void continuation_shouldBindPrincipalIsolationRouteRunAndTypeAndRejectReplay() {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxProcessLocalContinuations(2)
                .continuationTtl(Duration.ofMinutes(1))
                .build();
        HostingContinuationRegistry registry = new HostingContinuationRegistry(limits);
        HostingRequestContext owner = context("owner", "tenant-a");
        HostingContinuationDescriptor descriptor =
                registry.issue(binding(owner, "run-1"), "payload", List.of(), () -> {});

        // Act / Assert
        assertThatThrownBy(() -> registry.consume(
                        context("other", "tenant-a"),
                        HostingRouteKind.AGENT,
                        "route",
                        "run-1",
                        HostingContinuationType.INPUT,
                        descriptor.token()))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> registry.consume(
                        owner,
                        HostingRouteKind.AGENT,
                        "route",
                        "different-run",
                        HostingContinuationType.INPUT,
                        descriptor.token()))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.CONFLICT);
        assertThat(registry.consume(
                        owner,
                        HostingRouteKind.AGENT,
                        "route",
                        "run-1",
                        HostingContinuationType.INPUT,
                        descriptor.token()))
                .isEqualTo("payload");
        assertThatThrownBy(() -> registry.consume(
                        owner,
                        HostingRouteKind.AGENT,
                        "route",
                        "run-1",
                        HostingContinuationType.INPUT,
                        descriptor.token()))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.CONTINUATION_REPLAYED);
        registry.close();
    }

    @Test
    void continuation_shouldExpireAndDiscardRetainedStateOnce() {
        // Arrange
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T00:00:00Z"));
        HostingLimits limits =
                HostingLimits.builder().continuationTtl(Duration.ofSeconds(10)).build();
        HostingContinuationRegistry registry = new HostingContinuationRegistry(limits, clock, new SecureRandom());
        HostingRequestContext owner = context("owner", "tenant-a");
        AtomicInteger discarded = new AtomicInteger();
        HostingContinuationDescriptor descriptor =
                registry.issue(binding(owner, "run-1"), "payload", List.of(), discarded::incrementAndGet);
        clock.advance(Duration.ofSeconds(11));

        // Act / Assert
        assertThatThrownBy(() -> registry.consume(
                        owner,
                        HostingRouteKind.AGENT,
                        "route",
                        "run-1",
                        HostingContinuationType.INPUT,
                        descriptor.token()))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.CONTINUATION_EXPIRED);
        assertThat(discarded).hasValue(1);
        assertThat(registry.availableCount()).isZero();
        registry.close();
        assertThat(discarded).hasValue(1);
    }

    @Test
    void continuation_shouldEnforceCapacityAndCloseRetainedState() {
        // Arrange
        HostingLimits limits =
                HostingLimits.builder().maxProcessLocalContinuations(1).build();
        HostingContinuationRegistry registry = new HostingContinuationRegistry(limits);
        HostingRequestContext owner = context("owner", "tenant-a");
        AtomicInteger discarded = new AtomicInteger();
        registry.issue(binding(owner, "run-1"), "one", List.of(), discarded::incrementAndGet);

        // Act / Assert
        assertThatThrownBy(() -> registry.issue(binding(owner, "run-2"), "two", List.of(), discarded::incrementAndGet))
                .isInstanceOf(HostingException.class)
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.TOO_MANY_REQUESTS);
        registry.close();
        assertThat(discarded).hasValue(1);
    }

    private static HostingContinuationRegistry.Binding binding(HostingRequestContext context, String runId) {
        return new HostingContinuationRegistry.Binding(
                context.principalId(),
                context.isolationId(),
                HostingRouteKind.AGENT,
                "route",
                runId,
                HostingContinuationType.INPUT);
    }

    private static HostingRequestContext context(String principal, String isolation) {
        return new HostingRequestContext(
                "request",
                "correlation",
                new HostingPrincipal(principal, isolation),
                Map.of(),
                Map.of(),
                new DefaultRunCancellation());
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        private void advance(Duration duration) {
            value = value.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }
}
