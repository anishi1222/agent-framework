// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.List;
import java.util.Objects;

/**
 * Registers one stable case and its fixture in the conformance manifest.
 *
 * @param caseId stable conformance case identifier
 * @param suiteId stable conformance suite identifier
 * @param matrixStatus matrix coverage classification
 * @param matrixAreas exact feature-parity matrix areas covered
 * @param fixture classpath-relative fixture location
 * @param kind expected fixture kind
 * @param sourceReferences cross-language source or specification references
 */
public record ManifestCase(
        String caseId,
        String suiteId,
        String matrixStatus,
        List<String> matrixAreas,
        String fixture,
        FixtureKind kind,
        List<String> sourceReferences) {
    private static final String FIXTURE_ROOT = "conformance/v1/";

    /** Creates and validates a manifest case. */
    public ManifestCase {
        FixtureValidation.requireMatch(caseId, FixtureValidation.CASE_ID, "caseId");
        FixtureValidation.requireMatch(suiteId, FixtureValidation.SUITE_ID, "suiteId");
        FixtureValidation.requireNonBlank(matrixStatus, "matrixStatus");
        fixture = normalizedFixturePath(fixture);
        Objects.requireNonNull(kind, "kind");
        matrixAreas = List.copyOf(matrixAreas);
        sourceReferences = List.copyOf(sourceReferences);
        if (!caseId.startsWith(suiteId + "-")) {
            throw new ConformanceValidationException(caseId + " does not belong to suite " + suiteId + ".");
        }
        if ("initial-scope".equals(matrixStatus) && matrixAreas.isEmpty()) {
            throw new ConformanceValidationException(caseId + " must name an initial-scope matrix area.");
        }
    }

    static String normalizedFixturePath(String fixture) {
        FixtureValidation.requireNonBlank(fixture, "fixture");
        if (fixture.startsWith("/")
                || fixture.indexOf('\\') >= 0
                || !fixture.startsWith(FIXTURE_ROOT)
                || !fixture.endsWith(".json")) {
            throw invalidFixturePath(fixture);
        }
        String[] segments = fixture.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalidFixturePath(fixture);
            }
        }
        String normalized = String.join("/", segments);
        if (!normalized.equals(fixture)) {
            throw invalidFixturePath(fixture);
        }
        return normalized;
    }

    private static ConformanceValidationException invalidFixturePath(String fixture) {
        return new ConformanceValidationException("Invalid fixture resource path '" + fixture
                + "': expected a normalized path under conformance/v1/ with no absolute, "
                + "backslash, dot, or dot-dot segments.");
    }
}
