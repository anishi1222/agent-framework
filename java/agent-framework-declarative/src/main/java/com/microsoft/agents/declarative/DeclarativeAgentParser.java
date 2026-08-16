// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import java.nio.file.Path;

/** Provides concise strict JSON and YAML entry points for prompt-agent definitions. */
public final class DeclarativeAgentParser {
    private DeclarativeAgentParser() {}

    /**
     * Parses a strict JSON prompt-agent definition.
     *
     * @param json complete JSON document
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parseJson(String json) {
        return PromptAgentDefinitionParser.parseJson(json);
    }

    /**
     * Parses a strict YAML prompt-agent definition.
     *
     * @param yaml complete YAML document
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parseYaml(String yaml) {
        return PromptAgentDefinitionParser.parseYaml(yaml);
    }

    /**
     * Parses a UTF-8 JSON or YAML prompt-agent file.
     *
     * @param path definition path
     * @return immutable prompt-agent definition
     */
    public static PromptAgentDefinition parse(Path path) {
        return PromptAgentDefinitionParser.parse(path);
    }
}
