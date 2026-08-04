// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

/**
 * Defines the common immutable surface of every conformance fixture.
 */
public sealed interface ConformanceFixture permits BehaviorFixture, EventHistoryFixture, SnapshotFixture {
    /**
     * Returns the fixture schema version.
     *
     * @return schema version
     */
    int schemaVersion();

    /**
     * Returns the stable conformance case identifier.
     *
     * @return case identifier
     */
    String caseId();

    /**
     * Returns the fixture kind.
     *
     * @return fixture kind
     */
    FixtureKind kind();

    /**
     * Returns the behavior described by the fixture.
     *
     * @return human-readable behavior description
     */
    String description();

    /**
     * Returns the observable expected data.
     *
     * @return expected data
     */
    ConformanceValue.ObjectValue expected();
}
