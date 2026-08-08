// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Describes one advertised agent skill.
 *
 * @param id stable skill identifier
 * @param name display name
 * @param description skill description
 * @param tags ordered search tags
 * @param examples ordered example inputs
 * @param inputModes optional skill-specific input modes
 * @param outputModes optional skill-specific output modes
 * @param securityRequirements alternative security requirements
 * @param metadata immutable extension metadata
 */
public record AgentSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes,
        List<SecurityRequirement> securityRequirements,
        Map<String, StateValue> metadata) {
    /** Creates an immutable validated skill. */
    public AgentSkill {
        id = A2AValidation.nonBlank(id, "id");
        name = A2AValidation.nonBlank(name, "name");
        description = A2AValidation.nonBlank(description, "description");
        tags = A2AValidation.strings(tags, "tags", true);
        examples = A2AValidation.strings(examples, "examples", true);
        inputModes = A2AValidation.strings(inputModes, "inputModes", true);
        outputModes = A2AValidation.strings(outputModes, "outputModes", true);
        securityRequirements = A2AValidation.list(securityRequirements, "securityRequirements");
        metadata = A2AValidation.metadata(metadata, "metadata");
    }

    /**
     * Creates a skill builder.
     *
     * @param id stable skill identifier
     * @param name display name
     * @param description description
     * @return builder
     */
    public static Builder builder(String id, String name, String description) {
        return new Builder(id, name, description);
    }

    /** Builds an immutable {@link AgentSkill}. */
    public static final class Builder {
        private final String id;
        private final String name;
        private final String description;
        private List<String> tags = List.of();
        private List<String> examples = List.of();
        private List<String> inputModes = List.of();
        private List<String> outputModes = List.of();
        private List<SecurityRequirement> securityRequirements = List.of();
        private Map<String, StateValue> metadata = Map.of();

        private Builder(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }

        /** Sets search tags. */
        public Builder tags(List<String> values) {
            tags = values;
            return this;
        }

        /** Sets example inputs. */
        public Builder examples(List<String> values) {
            examples = values;
            return this;
        }

        /** Sets skill-specific input modes. */
        public Builder inputModes(List<String> values) {
            inputModes = values;
            return this;
        }

        /** Sets skill-specific output modes. */
        public Builder outputModes(List<String> values) {
            outputModes = values;
            return this;
        }

        /** Sets alternative security requirements. */
        public Builder securityRequirements(List<SecurityRequirement> values) {
            securityRequirements = values;
            return this;
        }

        /** Sets extension metadata. */
        public Builder metadata(Map<String, StateValue> values) {
            metadata = values;
            return this;
        }

        /** Creates the immutable skill. */
        public AgentSkill build() {
            return new AgentSkill(
                    id, name, description, tags, examples, inputModes, outputModes, securityRequirements, metadata);
        }
    }
}
