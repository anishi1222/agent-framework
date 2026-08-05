// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Describes immutable provider-neutral tool metadata and schemas.
 *
 * @param name stable non-blank tool name
 * @param description human-readable description
 * @param capabilities non-empty provider-neutral capability set
 * @param approvalMode approval policy for local execution
 * @param inputSchema explicit JSON Schema object for accepted arguments
 * @param outputSchema explicit JSON Schema object for the return value
 */
public record ToolMetadata(
        String name,
        String description,
        Set<ToolCapability> capabilities,
        ToolApprovalMode approvalMode,
        StateValue.ObjectValue inputSchema,
        StateValue.ObjectValue outputSchema) {
    /** Creates validated immutable tool metadata. */
    public ToolMetadata {
        name = ToolValidation.requireNonBlank(name, "name");
        description = Objects.requireNonNull(description, "description");
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty.");
        }
        capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        Objects.requireNonNull(approvalMode, "approvalMode");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        ToolSchemaValidator.validateSchema(inputSchema, "$inputSchema");
        ToolSchemaValidator.validateSchema(outputSchema, "$outputSchema");
    }
}
