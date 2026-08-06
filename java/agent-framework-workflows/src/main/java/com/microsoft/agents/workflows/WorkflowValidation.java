// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateCodec;

final class WorkflowValidation {
    private WorkflowValidation() {}

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new WorkflowValidationException(name + " must not be blank.");
        }
        return value;
    }

    static void requireCodec(StateCodec<?> codec) {
        String typeId = requireNonBlank(codec.typeId(), "codec typeId");
        if (!typeId.contains(".") || codec.currentVersion() <= 0) {
            throw new WorkflowValidationException(
                    "State codecs require a package-qualified typeId and a positive currentVersion.");
        }
    }
}
