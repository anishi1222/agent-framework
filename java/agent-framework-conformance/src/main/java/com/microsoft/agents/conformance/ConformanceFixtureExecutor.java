// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

/**
 * Adapts a conformance fixture to a future framework implementation.
 *
 * @param <F> supported fixture type
 */
@FunctionalInterface
public interface ConformanceFixtureExecutor<F extends ConformanceFixture> {
    /**
     * Executes the implementation under test and returns its observable data.
     *
     * @param fixture fixture to execute
     * @return language-neutral actual data
     * @throws Exception when the implementation cannot complete the case
     */
    ConformanceValue.ObjectValue execute(F fixture) throws Exception;
}
