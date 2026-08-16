// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contains all registered conformance cases for one fixture schema version.
 *
 * @param schemaVersion manifest schema version
 * @param cases registered cases
 */
public record ConformanceManifest(int schemaVersion, List<ManifestCase> cases) {
    /** Creates and validates a conformance manifest. */
    public ConformanceManifest {
        if (schemaVersion != FixtureValidation.SUPPORTED_SCHEMA_VERSION) {
            throw new ConformanceValidationException("Unsupported manifest schemaVersion " + schemaVersion + ".");
        }
        cases = List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new ConformanceValidationException("Manifest cases must not be empty.");
        }
        Set<String> caseIds = new HashSet<>();
        Set<String> fixtures = new HashSet<>();
        for (ManifestCase manifestCase : cases) {
            if (!caseIds.add(manifestCase.caseId())) {
                throw new ConformanceValidationException("Duplicate manifest caseId '" + manifestCase.caseId() + "'.");
            }
            String normalizedFixture = ManifestCase.normalizedFixturePath(manifestCase.fixture());
            if (!fixtures.add(normalizedFixture)) {
                throw new ConformanceValidationException("Duplicate fixture registration '" + normalizedFixture + "'.");
            }
        }
    }
}
