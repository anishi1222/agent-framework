// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Caches immutable skill lists with optional context-key isolation and refresh.
 *
 * <p>Concurrent requests for one cache key share one in-flight fetch. Failed or cancelled fetches
 * are not cached, and a failed refresh leaves the prior successful entry available for a later
 * retry.
 */
public final class CachingSkillsSource extends DelegatingSkillsSource {
    private static final String SHARED_KEY = "CachingSkillsSource-SharedCacheKey";

    private final Duration refreshInterval;
    private final Function<SkillsSourceContext, String> keySelector;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * Creates a non-expiring shared cache.
     *
     * @param innerSource decorated source
     */
    public CachingSkillsSource(SkillsSource innerSource) {
        this(innerSource, null, ignored -> SHARED_KEY);
    }

    /**
     * Creates a configured cache.
     *
     * @param innerSource decorated source
     * @param refreshInterval refresh interval, {@code null} to never expire
     * @param keySelector context isolation key selector
     */
    public CachingSkillsSource(
            SkillsSource innerSource, Duration refreshInterval, Function<SkillsSourceContext, String> keySelector) {
        super(innerSource);
        this.refreshInterval = refreshInterval;
        this.keySelector = Objects.requireNonNull(keySelector, "keySelector");
    }

    @Override
    public CompletionStage<List<Skill>> getSkillsAsync(SkillsSourceContext context, RunCancellation cancellation) {
        Objects.requireNonNull(context, "context");
        SkillValidation.requireActive(cancellation);
        String selected = keySelector.apply(context);
        String key = selected == null ? SHARED_KEY : selected;
        if (key.isBlank()) {
            throw new IllegalArgumentException("cache isolation key must not be blank.");
        }
        Slot slot = slots.computeIfAbsent(key, ignored -> new Slot());
        synchronized (slot) {
            long now = System.nanoTime();
            if (slot.value != null && !isStale(slot, now)) {
                return CompletableFuture.completedFuture(slot.value);
            }
            if (slot.inFlight != null) {
                return slot.inFlight.minimalCompletionStage();
            }
            CompletableFuture<List<Skill>> inFlight = innerSource()
                    .getSkillsAsync(context, cancellation)
                    .thenApply(skills -> List.copyOf(Objects.requireNonNull(skills, "skills")))
                    .toCompletableFuture();
            slot.inFlight = inFlight;
            inFlight.whenComplete((skills, failure) -> {
                synchronized (slot) {
                    if (failure == null) {
                        slot.value = skills;
                        slot.loadedAtNanos = System.nanoTime();
                    }
                    if (slot.inFlight == inFlight) {
                        slot.inFlight = null;
                    }
                }
            });
            return inFlight.minimalCompletionStage();
        }
    }

    private boolean isStale(Slot slot, long now) {
        if (refreshInterval == null) {
            return false;
        }
        if (refreshInterval.isZero() || refreshInterval.isNegative()) {
            return true;
        }
        long elapsed = now - slot.loadedAtNanos;
        long limit;
        try {
            limit = refreshInterval.toNanos();
        } catch (ArithmeticException exception) {
            limit = Long.MAX_VALUE;
        }
        return elapsed >= limit;
    }

    private static final class Slot {
        private List<Skill> value;
        private long loadedAtNanos;
        private CompletableFuture<List<Skill>> inFlight;
    }
}
