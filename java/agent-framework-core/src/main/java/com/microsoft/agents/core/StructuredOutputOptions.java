// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Defines a provider-neutral JSON Schema response contract.
 *
 * <p>The schema is sent to providers that support native structured output. Local decoding is
 * intentionally separate through {@link StructuredOutputs}, so public options remain independent
 * of a JSON binding library or application model type.
 *
 * @param name portable schema name using letters, digits, underscores, or dashes
 * @param description optional non-blank schema description
 * @param schema JSON Schema document
 * @param strict whether providers should require strict schema adherence when supported
 */
public record StructuredOutputOptions(String name, String description, StateValue.ObjectValue schema, boolean strict) {
    /** Creates validated immutable structured-output options. */
    public StructuredOutputOptions {
        name = CoreValidation.requireNonBlank(name, "name");
        if (name.length() > 64 || !name.matches("[A-Za-z0-9_-]+")) {
            throw new ValidationException(
                    "name must contain only letters, digits, underscores, or dashes and be at most 64 characters.");
        }
        description = CoreValidation.optionalNonBlank(description, "description");
        schema = Objects.requireNonNull(schema, "schema");
    }

    /**
     * Creates strict JSON Schema output options.
     *
     * @param name portable schema name
     * @param schema JSON Schema document
     * @return strict structured-output options
     */
    public static StructuredOutputOptions jsonSchema(String name, StateValue.ObjectValue schema) {
        return new StructuredOutputOptions(name, null, schema, true);
    }

    /**
     * Creates strict JSON Schema output options from schema members.
     *
     * @param name portable schema name
     * @param schema JSON Schema members
     * @return strict structured-output options
     */
    public static StructuredOutputOptions jsonSchema(String name, Map<String, StateValue> schema) {
        return jsonSchema(name, StateValue.object(schema));
    }

    /**
     * Creates a builder.
     *
     * @return structured-output builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable {@link StructuredOutputOptions}. */
    public static final class Builder {
        private String name;

        private String description;

        private StateValue.ObjectValue schema;

        private boolean strict = true;

        private Builder() {}

        /** Sets the portable schema name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Sets the optional schema description. */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Sets the JSON Schema document. */
        public Builder schema(StateValue.ObjectValue schema) {
            this.schema = schema;
            return this;
        }

        /** Sets whether providers should require strict schema adherence. */
        public Builder strict(boolean strict) {
            this.strict = strict;
            return this;
        }

        /**
         * Creates immutable structured-output options.
         *
         * @return structured-output options
         */
        public StructuredOutputOptions build() {
            return new StructuredOutputOptions(name, description, schema, strict);
        }
    }
}
