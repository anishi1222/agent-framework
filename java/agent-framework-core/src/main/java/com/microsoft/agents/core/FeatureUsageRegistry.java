// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Accumulates coarse feature-usage bits for the lifetime of a process.
 *
 * <p>The global registry is monotonic and intentionally never reset. A set bit means only that the
 * feature was observed at least once before the token was read; it is not an invocation count or a
 * request-scoped attribution signal.
 */
public final class FeatureUsageRegistry {
    /** Environment variable that disables only feature-mask marking and emission. */
    public static final String FEATURE_MASK_DISABLED_ENV_VAR = "AGENT_FRAMEWORK_FEATURE_MASK_DISABLED";

    /** Version of the Java feature-index registry. */
    public static final int REGISTRY_VERSION = 1;

    /** Number of feature indexes available in registry version 1. */
    public static final int WIDTH = 128;

    private static final FeatureUsageRegistry GLOBAL =
            new FeatureUsageRegistry(REGISTRY_VERSION, FeatureUsageRegistry::globalFeatureMaskEnabled);

    private final int registryVersion;

    private final BooleanSupplier enabled;

    private BigInteger mask = BigInteger.ZERO;

    /** Creates an enabled registry using the current Java registry version. */
    public FeatureUsageRegistry() {
        this(REGISTRY_VERSION, true);
    }

    /**
     * Creates a registry with explicit enablement.
     *
     * @param registryVersion positive registry version
     * @param enabled whether marking and token emission are enabled
     */
    public FeatureUsageRegistry(int registryVersion, boolean enabled) {
        this(registryVersion, () -> enabled);
    }

    private FeatureUsageRegistry(int registryVersion, BooleanSupplier enabled) {
        if (registryVersion <= 0) {
            throw new IllegalArgumentException("registryVersion must be greater than zero.");
        }
        this.registryVersion = registryVersion;
        this.enabled = java.util.Objects.requireNonNull(enabled, "enabled");
    }

    /**
     * Returns the process-global monotonic registry.
     *
     * @return global feature registry
     */
    public static FeatureUsageRegistry global() {
        return GLOBAL;
    }

    /**
     * Returns the registry encoding version.
     *
     * @return positive registry version
     */
    public int registryVersion() {
        return registryVersion;
    }

    /**
     * Marks a feature as observed.
     *
     * <p>Repeated marks are idempotent. When telemetry is disabled, this method is a no-op.
     *
     * @param index feature index in the inclusive range {@code 0..127}
     */
    synchronized void markUsed(int index) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        validateIndex(index);
        mask = mask.setBit(index);
    }

    /**
     * Marks a declared feature as observed.
     *
     * @param index published feature descriptor
     */
    public void markUsed(FeatureUsageIndex index) {
        markUsed(java.util.Objects.requireNonNull(index, "index").value());
    }

    /**
     * Returns whether an index has been observed.
     *
     * @param index feature index in the inclusive range {@code 0..127}
     * @return {@code true} when the bit is set
     */
    synchronized boolean isMarked(int index) {
        validateIndex(index);
        return mask.testBit(index);
    }

    /**
     * Returns whether a declared feature has been observed.
     *
     * @param index published feature descriptor
     * @return {@code true} when the bit is set
     */
    public boolean isMarked(FeatureUsageIndex index) {
        return isMarked(java.util.Objects.requireNonNull(index, "index").value());
    }

    /**
     * Returns the live versioned feature token.
     *
     * @return {@code v&lt;version&gt;.&lt;lowercase-hex-mask&gt;}, or empty when disabled or unused
     */
    public synchronized Optional<String> token() {
        if (!enabled.getAsBoolean() || mask.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of("v" + registryVersion + "." + mask.toString(16));
    }

    private static void validateIndex(int index) {
        if (index < 0 || index >= WIDTH) {
            throw new IllegalArgumentException("Feature index must be in range 0..127, got " + index + ".");
        }
    }

    private static boolean globalFeatureMaskEnabled() {
        return UserAgentUtil.isTelemetryEnabled()
                && !UserAgentUtil.isEnvironmentFlagEnabled(FEATURE_MASK_DISABLED_ENV_VAR);
    }
}
