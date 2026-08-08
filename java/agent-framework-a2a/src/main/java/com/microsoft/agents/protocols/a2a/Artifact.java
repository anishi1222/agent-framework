// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Represents an immutable task output artifact.
 *
 * @param artifactId identifier unique within its task
 * @param name optional display name
 * @param description optional description
 * @param parts ordered non-empty output parts
 * @param metadata immutable metadata
 * @param extensions extension URIs
 */
public record Artifact(
        String artifactId,
        String name,
        String description,
        List<Part> parts,
        Map<String, StateValue> metadata,
        List<URI> extensions) {
    /** Creates an immutable validated artifact. */
    public Artifact {
        artifactId = A2AValidation.nonBlank(artifactId, "artifactId");
        name = A2AValidation.optionalNonBlank(name, "name");
        description = A2AValidation.optionalNonBlank(description, "description");
        parts = A2AValidation.nonEmptyList(parts, "parts");
        metadata = A2AValidation.metadata(metadata, "metadata");
        extensions = A2AValidation.list(extensions, "extensions").stream()
                .map(uri -> A2AValidation.absoluteUri(uri, "extension"))
                .toList();
    }

    /**
     * Creates an artifact builder.
     *
     * @param artifactId artifact identifier
     * @return builder
     */
    public static Builder builder(String artifactId) {
        return new Builder(artifactId);
    }

    /** Builds an immutable {@link Artifact}. */
    public static final class Builder {
        private final String artifactId;
        private String name;
        private String description;
        private List<Part> parts = List.of();
        private Map<String, StateValue> metadata = Map.of();
        private List<URI> extensions = List.of();

        private Builder(String artifactId) {
            this.artifactId = artifactId;
        }

        /** Sets the display name. */
        public Builder name(String value) {
            name = value;
            return this;
        }

        /** Sets the description. */
        public Builder description(String value) {
            description = value;
            return this;
        }

        /** Sets artifact parts. */
        public Builder parts(List<? extends Part> values) {
            parts = List.copyOf(values);
            return this;
        }

        /** Sets artifact metadata. */
        public Builder metadata(Map<String, StateValue> values) {
            metadata = values;
            return this;
        }

        /** Sets extension URIs. */
        public Builder extensions(List<URI> values) {
            extensions = values;
            return this;
        }

        /** Creates the immutable artifact. */
        public Artifact build() {
            return new Artifact(artifactId, name, description, parts, metadata, extensions);
        }
    }
}
