// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;
import java.util.regex.Pattern;

final class FixtureValidation {
    static final int SUPPORTED_SCHEMA_VERSION = 1;

    static final Pattern CASE_ID = Pattern.compile("JCF-[A-Z]+(?:-[A-Z]+)*-[0-9]{3}");

    static final Pattern SUITE_ID = Pattern.compile("JCF-[A-Z]+(?:-[A-Z]+)*");

    private FixtureValidation() {}

    static void validateCommon(int schemaVersion, String caseId, String description) {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new ConformanceValidationException("Unsupported schemaVersion " + schemaVersion + ".");
        }
        requireMatch(caseId, CASE_ID, "caseId");
        requireNonBlank(description, "description");
    }

    static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new ConformanceValidationException(field + " must not be blank.");
        }
        return value;
    }

    static void requireMatch(String value, Pattern pattern, String field) {
        requireNonBlank(value, field);
        if (!pattern.matcher(value).matches()) {
            throw new ConformanceValidationException(field + " has invalid value '" + value + "'.");
        }
    }
}
