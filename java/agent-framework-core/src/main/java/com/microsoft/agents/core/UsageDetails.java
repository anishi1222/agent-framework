// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Represents immutable standard and provider-specific usage values.
 *
 * <p>Values retain their JSON shape so streaming aggregation can apply Python-compatible
 * pairwise folds. During a fold, a missing or JSON-null value contributes zero, integral numbers
 * are summed without overflow, and a key with a non-integral value on either side is dropped for
 * that pair. A later update can therefore reintroduce a previously dropped key.
 */
public final class UsageDetails {
    /** Stable input-token key used by Java conformance fixtures. */
    public static final String INPUT_TOKENS = "inputTokens";

    /** Stable output-token key used by Java conformance fixtures. */
    public static final String OUTPUT_TOKENS = "outputTokens";

    /** Stable total-token key. */
    public static final String TOTAL_TOKENS = "totalTokens";

    /** Stable provider-cache creation input-token key. */
    public static final String CACHE_CREATION_INPUT_TOKENS = "cacheCreationInputTokens";

    /** Stable provider-cache read input-token key. */
    public static final String CACHE_READ_INPUT_TOKENS = "cacheReadInputTokens";

    /** Stable reasoning output-token key. */
    public static final String REASONING_OUTPUT_TOKENS = "reasoningOutputTokens";

    private final Map<String, StateValue> values;

    /**
     * Creates usage details from JSON-shaped values.
     *
     * @param values usage values; keys and values must be non-null
     */
    public UsageDetails(Map<String, StateValue> values) {
        this.values = CoreValidation.copyStateMap(values, "values");
    }

    /**
     * Returns empty usage details.
     *
     * @return empty usage
     */
    public static UsageDetails empty() {
        return new UsageDetails(Map.of());
    }

    /**
     * Creates usage containing one integer value.
     *
     * @param key non-blank key
     * @param value integer value
     * @return usage details
     */
    public static UsageDetails of(String key, long value) {
        return new UsageDetails(Map.of(key, StateValue.integer(value)));
    }

    /**
     * Creates a usage builder.
     *
     * @return usage builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns immutable usage values.
     *
     * @return usage values
     */
    public Map<String, StateValue> values() {
        return values;
    }

    /**
     * Returns an arbitrary-precision integer value when the named entry is integral.
     *
     * @param key usage key
     * @return integer value, or empty for missing, null, decimal, or non-numeric entries
     */
    public Optional<BigInteger> integer(String key) {
        StateValue value = values.get(CoreValidation.requireNonBlank(key, "key"));
        return integralValue(value);
    }

    /**
     * Returns the input-token count when present and integral.
     *
     * @return input-token count
     */
    public Optional<BigInteger> inputTokens() {
        return integer(INPUT_TOKENS);
    }

    /**
     * Returns the output-token count when present and integral.
     *
     * @return output-token count
     */
    public Optional<BigInteger> outputTokens() {
        return integer(OUTPUT_TOKENS);
    }

    /**
     * Returns the total-token count when present and integral.
     *
     * @return total-token count
     */
    public Optional<BigInteger> totalTokens() {
        return integer(TOTAL_TOKENS);
    }

    /**
     * Applies the sequential pairwise usage fold used by response aggregation.
     *
     * @param other next usage update
     * @return a new folded usage value
     */
    public UsageDetails fold(UsageDetails other) {
        Objects.requireNonNull(other, "other");
        Set<String> keys = new LinkedHashSet<>(values.keySet());
        keys.addAll(other.values.keySet());
        LinkedHashMap<String, StateValue> result = new LinkedHashMap<>();
        for (String key : keys) {
            Optional<BigInteger> left = foldOperand(values.get(key));
            Optional<BigInteger> right = foldOperand(other.values.get(key));
            if (left.isPresent() && right.isPresent()) {
                result.put(key, StateValue.integer(left.orElseThrow().add(right.orElseThrow())));
            }
        }
        return new UsageDetails(result);
    }

    private static Optional<BigInteger> foldOperand(StateValue value) {
        if (value == null || value == StateValue.NullValue.INSTANCE) {
            return Optional.of(BigInteger.ZERO);
        }
        return integralValue(value);
    }

    private static Optional<BigInteger> integralValue(StateValue value) {
        if (!(value instanceof StateValue.NumberValue number) || number.value().scale() > 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(number.value().toBigIntegerExact());
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof UsageDetails usage && values.equals(usage.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    /** Builds immutable {@link UsageDetails}. */
    public static final class Builder {
        private final LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Sets any JSON-shaped usage value.
         *
         * @param key non-blank usage key
         * @param value non-null usage value
         * @return this builder
         */
        public Builder value(String key, StateValue value) {
            values.put(CoreValidation.requireNonBlank(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets any arbitrary-precision integer usage value.
         *
         * @param key non-blank usage key
         * @param value integer value
         * @return this builder
         */
        public Builder integer(String key, BigInteger value) {
            return value(key, StateValue.integer(value));
        }

        /**
         * Sets the input-token count.
         *
         * @param value token count
         * @return this builder
         */
        public Builder inputTokens(long value) {
            return value(INPUT_TOKENS, StateValue.integer(value));
        }

        /**
         * Sets the output-token count.
         *
         * @param value token count
         * @return this builder
         */
        public Builder outputTokens(long value) {
            return value(OUTPUT_TOKENS, StateValue.integer(value));
        }

        /**
         * Sets the total-token count.
         *
         * @param value token count
         * @return this builder
         */
        public Builder totalTokens(long value) {
            return value(TOTAL_TOKENS, StateValue.integer(value));
        }

        /**
         * Creates immutable usage details.
         *
         * @return usage details
         */
        public UsageDetails build() {
            return new UsageDetails(values);
        }
    }
}
