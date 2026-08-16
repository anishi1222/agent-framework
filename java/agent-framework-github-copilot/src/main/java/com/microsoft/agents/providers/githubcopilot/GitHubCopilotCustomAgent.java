// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.List;

/**
 * Declares an official SDK custom Copilot agent.
 *
 * @param name stable mention name
 * @param displayName user-facing name
 * @param description user-facing description
 * @param prompt agent prompt
 * @param tools immutable allowed tool names
 * @param skills immutable skill names
 * @param model optional model
 */
public record GitHubCopilotCustomAgent(
        String name,
        String displayName,
        String description,
        String prompt,
        List<String> tools,
        List<String> skills,
        String model) {
    /** Creates and defensively copies a custom-agent configuration. */
    public GitHubCopilotCustomAgent {
        name = required(name, "name");
        displayName = required(displayName, "displayName");
        description = required(description, "description");
        prompt = required(prompt, "prompt");
        tools = List.copyOf(tools);
        skills = List.copyOf(skills);
        model = model == null || model.isBlank() ? null : model;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
