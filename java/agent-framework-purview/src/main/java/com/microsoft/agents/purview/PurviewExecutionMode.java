// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/** Identifies whether policy evaluation blocks the foreground interaction. */
public enum PurviewExecutionMode {
    /** Wait for processContent before proceeding. */
    EVALUATE_INLINE("evaluateInline"),
    /** Process content asynchronously. */
    EVALUATE_OFFLINE("evaluateOffline"),
    /** Unknown future value, which is never treated as inline success. */
    UNKNOWN("unknownFutureValue");

    private final String graphValue;

    PurviewExecutionMode(String graphValue) {
        this.graphValue = graphValue;
    }

    /** Returns the Microsoft Graph value. */
    public String graphValue() {
        return graphValue;
    }

    /** Resolves a service value without a success-shaped fallback. */
    public static PurviewExecutionMode fromGraphValue(String value) {
        if ("evaluateInline".equals(value)) {
            return EVALUATE_INLINE;
        }
        if ("evaluateOffline".equals(value)) {
            return EVALUATE_OFFLINE;
        }
        return UNKNOWN;
    }
}
