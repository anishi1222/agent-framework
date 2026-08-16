// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;

/**
 * Represents one validated RFC 6902 JSON Patch operation.
 *
 * @param op operation
 * @param path target JSON Pointer
 * @param from source JSON Pointer for move or copy
 * @param value value for add, replace, or test
 */
public record AGUIJsonPatchOperation(Operation op, String path, String from, StateValue value) {
    /** Creates a structurally valid patch operation. */
    public AGUIJsonPatchOperation {
        java.util.Objects.requireNonNull(op, "op");
        AGUIJsonPointer.parse(path);
        if (from != null) {
            AGUIJsonPointer.parse(from);
        }
        boolean needsValue = op == Operation.ADD || op == Operation.REPLACE || op == Operation.TEST;
        boolean needsFrom = op == Operation.MOVE || op == Operation.COPY;
        if (needsValue != (value != null)) {
            throw invalid("Patch value presence does not match operation.");
        }
        if (needsFrom != (from != null)) {
            throw invalid("Patch from presence does not match operation.");
        }
    }

    /** Lists the six RFC 6902 operation names. */
    public enum Operation {
        /** Adds or replaces an object member, or inserts an array item. */
        ADD("add"),
        /** Removes an existing value. */
        REMOVE("remove"),
        /** Replaces an existing value. */
        REPLACE("replace"),
        /** Moves an existing value. */
        MOVE("move"),
        /** Copies an existing value. */
        COPY("copy"),
        /** Asserts exact value equality. */
        TEST("test");

        private final String value;

        Operation(String value) {
            this.value = value;
        }

        /**
         * Returns the lower-case wire value.
         *
         * @return wire value
         */
        public String value() {
            return value;
        }

        /**
         * Resolves a lower-case wire value.
         *
         * @param value wire value
         * @return operation
         */
        public static Operation fromValue(String value) {
            for (Operation operation : values()) {
                if (operation.value.equals(value)) {
                    return operation;
                }
            }
            throw invalid("Unknown JSON Patch operation.");
        }
    }

    private static AGUIProtocolException invalid(String message) {
        return new AGUIProtocolException(AGUIErrorCode.INVALID_PATCH, message);
    }
}
