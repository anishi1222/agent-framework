// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.lang.reflect.AnnotatedElement;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Inspects lifecycle annotations and coordinates optional one-time runtime warnings. */
public final class FeatureStages {
    private static final Set<WarningKey> WARNED_FEATURES = ConcurrentHashMap.newKeySet();

    private FeatureStages() {}

    /**
     * Returns runtime lifecycle metadata declared on an element.
     *
     * @param element annotated element
     * @return staged metadata, or empty for a generally available element
     * @throws ValidationException when both stage annotations are present
     */
    public static Optional<FeatureStageMetadata> describe(AnnotatedElement element) {
        AnnotatedElement safeElement = Objects.requireNonNull(element, "element");
        Experimental experimental = safeElement.getAnnotation(Experimental.class);
        ReleaseCandidate releaseCandidate = safeElement.getAnnotation(ReleaseCandidate.class);
        if (experimental != null && releaseCandidate != null) {
            throw new ValidationException("An API cannot be both experimental and release-candidate.");
        }
        if (experimental != null) {
            return Optional.of(new FeatureStageMetadata(FeatureStage.EXPERIMENTAL, experimental.value()));
        }
        if (releaseCandidate != null) {
            return Optional.of(new FeatureStageMetadata(FeatureStage.RELEASE_CANDIDATE, releaseCandidate.value()));
        }
        return Optional.empty();
    }

    /**
     * Emits a warning at most once per stage and feature identifier in this process.
     *
     * @param element annotated element
     * @param warningSink warning consumer
     * @return {@code true} when a warning was emitted
     */
    public static boolean warnOnce(AnnotatedElement element, Consumer<String> warningSink) {
        Consumer<String> safeSink = Objects.requireNonNull(warningSink, "warningSink");
        Optional<FeatureStageMetadata> described = describe(element);
        if (described.isEmpty()) {
            return false;
        }
        FeatureStageMetadata metadata = described.orElseThrow();
        WarningKey key = new WarningKey(metadata.stage(), metadata.featureId());
        if (!WARNED_FEATURES.add(key)) {
            return false;
        }
        safeSink.accept(metadata.warningMessage(displayName(element)));
        return true;
    }

    static void clearWarningsForTesting() {
        WARNED_FEATURES.clear();
    }

    private static String displayName(AnnotatedElement element) {
        if (element instanceof Class<?> type) {
            return type.getName();
        }
        return element.toString();
    }

    private record WarningKey(FeatureStage stage, String featureId) {}
}
