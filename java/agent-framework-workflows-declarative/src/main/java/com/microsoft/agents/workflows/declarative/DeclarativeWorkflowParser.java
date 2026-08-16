// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import java.nio.file.Path;

/** Provides concise strict JSON and YAML entry points for declarative workflow definitions. */
public final class DeclarativeWorkflowParser {
    private DeclarativeWorkflowParser() {}

    /**
     * Parses a strict JSON workflow definition.
     *
     * @param json complete JSON document
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parseJson(String json) {
        return DeclarativeWorkflowDefinitionParser.parseJson(json);
    }

    /**
     * Parses a strict YAML workflow definition.
     *
     * @param yaml complete YAML document
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parseYaml(String yaml) {
        return DeclarativeWorkflowDefinitionParser.parseYaml(yaml);
    }

    /**
     * Parses a UTF-8 JSON or YAML workflow file.
     *
     * @param path definition path
     * @return immutable workflow definition
     */
    public static DeclarativeWorkflowDefinition parse(Path path) {
        return DeclarativeWorkflowDefinitionParser.parse(path);
    }
}
