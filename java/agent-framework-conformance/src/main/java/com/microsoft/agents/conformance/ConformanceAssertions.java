// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;

/**
 * Supplies implementation-neutral assertions for future module contract tests.
 */
public final class ConformanceAssertions {
    private ConformanceAssertions() {}

    /**
     * Compares actual observable data with a fixture's expected data.
     *
     * @param fixture source fixture
     * @param actual observable implementation result
     * @throws AssertionError when the data differs
     */
    public static void assertExpected(ConformanceFixture fixture, ConformanceValue.ObjectValue actual) {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(actual, "actual");
        if (!fixture.expected().equals(actual)) {
            throw new AssertionError(
                    "Conformance case " + fixture.caseId() + " expected " + fixture.expected() + " but was " + actual);
        }
    }

    /**
     * Executes an adapter and compares its result with the fixture.
     *
     * @param fixture source fixture
     * @param executor implementation adapter
     * @param <F> fixture type
     * @throws Exception when the adapter cannot complete
     * @throws AssertionError when the result differs
     */
    public static <F extends ConformanceFixture> void assertConforms(F fixture, ConformanceFixtureExecutor<F> executor)
            throws Exception {
        assertExpected(fixture, executor.execute(fixture));
    }
}
