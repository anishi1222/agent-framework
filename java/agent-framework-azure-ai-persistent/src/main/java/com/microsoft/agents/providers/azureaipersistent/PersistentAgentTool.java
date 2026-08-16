// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;
import java.util.Objects;

/**
 * Describes one provider tool without exposing Azure SDK models.
 *
 * @param kind tool family
 * @param name optional tool name
 * @param description optional description
 * @param schemaJson optional JSON Schema or OpenAPI document
 * @param resourceIds immutable file or vector-store identifiers
 * @param supported whether the pinned SDK supports this tool
 * @param limitation optional reason the tool is unsupported
 */
public record PersistentAgentTool(
        PersistentToolKind kind,
        String name,
        String description,
        String schemaJson,
        List<String> resourceIds,
        boolean supported,
        String limitation) {
    /** Creates and validates a tool definition. */
    public PersistentAgentTool {
        Objects.requireNonNull(kind, "kind");
        resourceIds = resourceIds == null
                ? List.of()
                : resourceIds.stream()
                        .map(value -> {
                            if (value == null || value.isBlank()) {
                                throw new IllegalArgumentException("resourceIds must not contain blank values.");
                            }
                            return value;
                        })
                        .toList();
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        if (schemaJson != null && schemaJson.isBlank()) {
            throw new IllegalArgumentException("schemaJson must not be blank.");
        }
        if (supported && limitation != null) {
            throw new IllegalArgumentException("A supported tool cannot have a limitation.");
        }
        if (!supported && (limitation == null || limitation.isBlank())) {
            throw new IllegalArgumentException("An unsupported tool requires a limitation.");
        }
    }

    /**
     * Creates a code-interpreter tool.
     *
     * @param fileIds optional uploaded file identifiers
     * @return tool
     */
    public static PersistentAgentTool codeInterpreter(List<String> fileIds) {
        return new PersistentAgentTool(PersistentToolKind.CODE_INTERPRETER, null, null, null, fileIds, true, null);
    }

    /**
     * Creates a file-search tool.
     *
     * @param vectorStoreIds vector-store identifiers
     * @return tool
     */
    public static PersistentAgentTool fileSearch(List<String> vectorStoreIds) {
        return new PersistentAgentTool(PersistentToolKind.FILE_SEARCH, null, null, null, vectorStoreIds, true, null);
    }

    /**
     * Creates a function tool.
     *
     * @param name function name
     * @param description function description
     * @param parametersJson JSON Schema
     * @return tool
     */
    public static PersistentAgentTool function(String name, String description, String parametersJson) {
        return new PersistentAgentTool(
                PersistentToolKind.FUNCTION, name, description, parametersJson, List.of(), true, null);
    }

    /**
     * Creates an anonymous-authentication OpenAPI tool.
     *
     * @param name tool name
     * @param description tool description
     * @param openApiJson OpenAPI document
     * @return tool
     */
    public static PersistentAgentTool openApi(String name, String description, String openApiJson) {
        return new PersistentAgentTool(
                PersistentToolKind.OPENAPI, name, description, openApiJson, List.of(), true, null);
    }

    /**
     * Represents an MCP tool unsupported by {@code azure-ai-agents-persistent:1.0.0-beta.2}.
     *
     * @param name server or tool name
     * @return explicit unsupported tool
     */
    public static PersistentAgentTool unsupportedMcp(String name) {
        return new PersistentAgentTool(
                PersistentToolKind.MCP,
                name,
                null,
                null,
                List.of(),
                false,
                "azure-ai-agents-persistent:1.0.0-beta.2 has no MCP tool model.");
    }
}
