// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides immutable indexed access to validated conformance fixtures.
 */
public final class ConformanceFixtureCatalog {
    private final ConformanceManifest manifest;

    private final Map<String, ConformanceFixture> fixtures;

    ConformanceFixtureCatalog(ConformanceManifest manifest, Map<String, ConformanceFixture> fixtures) {
        this.manifest = manifest;
        this.fixtures = Collections.unmodifiableMap(new LinkedHashMap<>(fixtures));
    }

    /**
     * Returns the validated manifest.
     *
     * @return conformance manifest
     */
    public ConformanceManifest manifest() {
        return manifest;
    }

    /**
     * Returns fixtures indexed by stable case identifier.
     *
     * @return immutable fixture index
     */
    public Map<String, ConformanceFixture> cases() {
        return fixtures;
    }

    /**
     * Returns a required fixture.
     *
     * @param caseId stable case identifier
     * @return matching fixture
     * @throws ConformanceValidationException when the case is not registered
     */
    public ConformanceFixture requireCase(String caseId) {
        ConformanceFixture fixture = fixtures.get(caseId);
        if (fixture == null) {
            throw new ConformanceValidationException("Conformance case '" + caseId + "' is not registered.");
        }
        return fixture;
    }

    /**
     * Returns fixtures belonging to a suite.
     *
     * @param suiteId stable suite identifier
     * @return fixtures in manifest order
     */
    public List<ConformanceFixture> bySuite(String suiteId) {
        return manifest.cases().stream()
                .filter(manifestCase -> manifestCase.suiteId().equals(suiteId))
                .map(manifestCase -> fixtures.get(manifestCase.caseId()))
                .toList();
    }

    /**
     * Returns fixtures with a particular schema kind.
     *
     * @param kind fixture kind
     * @return fixtures in manifest order
     */
    public List<ConformanceFixture> byKind(FixtureKind kind) {
        return manifest.cases().stream()
                .filter(manifestCase -> manifestCase.kind() == kind)
                .map(manifestCase -> fixtures.get(manifestCase.caseId()))
                .toList();
    }
}
