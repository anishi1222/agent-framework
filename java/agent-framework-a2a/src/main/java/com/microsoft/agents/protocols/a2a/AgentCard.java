// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents an immutable A2A v1 agent card.
 *
 * @param name human-readable agent name
 * @param description agent description
 * @param provider optional provider
 * @param version application-defined agent version
 * @param documentationUrl optional documentation URL
 * @param capabilities advertised capabilities
 * @param defaultInputModes default accepted media types
 * @param defaultOutputModes default produced media types
 * @param skills ordered skill declarations
 * @param securitySchemes named security schemes
 * @param securityRequirements alternative security requirements
 * @param iconUrl optional icon URL
 * @param supportedInterfaces ordered interfaces, preferred first
 * @param signatures optional card signatures
 * @param additionalProperties safely retained unknown additive properties
 */
public record AgentCard(
        String name,
        String description,
        AgentProvider provider,
        String version,
        URI documentationUrl,
        AgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentSkill> skills,
        Map<String, SecurityScheme> securitySchemes,
        List<SecurityRequirement> securityRequirements,
        URI iconUrl,
        List<AgentInterface> supportedInterfaces,
        List<AgentCardSignature> signatures,
        Map<String, StateValue> additionalProperties) {
    private static final Set<String> RESERVED_PROPERTIES = Set.of(
            "name",
            "description",
            "provider",
            "version",
            "documentationUrl",
            "capabilities",
            "defaultInputModes",
            "defaultOutputModes",
            "skills",
            "securitySchemes",
            "securityRequirements",
            "iconUrl",
            "supportedInterfaces",
            "signatures");

    /** Creates an immutable, strictly validated card. */
    public AgentCard {
        name = A2AValidation.nonBlank(name, "name");
        description = A2AValidation.nonBlank(description, "description");
        version = A2AValidation.nonBlank(version, "version");
        if (documentationUrl != null) {
            documentationUrl = A2AValidation.absoluteUri(documentationUrl, "documentationUrl");
        }
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        defaultInputModes = A2AValidation.strings(defaultInputModes, "defaultInputModes", false);
        defaultOutputModes = A2AValidation.strings(defaultOutputModes, "defaultOutputModes", false);
        skills = A2AValidation.list(skills, "skills");
        securitySchemes = A2AValidation.map(securitySchemes, "securitySchemes");
        securityRequirements = A2AValidation.list(securityRequirements, "securityRequirements");
        if (iconUrl != null) {
            iconUrl = A2AValidation.absoluteUri(iconUrl, "iconUrl");
        }
        supportedInterfaces = A2AValidation.nonEmptyList(supportedInterfaces, "supportedInterfaces");
        if (supportedInterfaces.stream().anyMatch(value -> !A2AProtocol.VERSION.equals(value.protocolVersion()))) {
            throw new ValidationException(
                    "Every supported interface must declare protocolVersion " + A2AProtocol.VERSION + ".");
        }
        signatures = A2AValidation.list(signatures, "signatures");
        additionalProperties = A2AValidation.metadata(additionalProperties, "additionalProperties");
        Set<String> collisions = new HashSet<>(additionalProperties.keySet());
        collisions.retainAll(RESERVED_PROPERTIES);
        if (!collisions.isEmpty()) {
            throw new ValidationException(
                    "additionalProperties must not replace reserved AgentCard fields: " + collisions + ".");
        }
        for (SecurityRequirement requirement : securityRequirements) {
            if (!securitySchemes.keySet().containsAll(requirement.schemes().keySet())) {
                throw new ValidationException("securityRequirements references an undeclared security scheme.");
            }
        }
    }

    /**
     * Creates an agent-card builder.
     *
     * @param name agent name
     * @param description description
     * @param version application version
     * @return builder
     */
    public static Builder builder(String name, String description, String version) {
        return new Builder(name, description, version);
    }

    /** Builds an immutable {@link AgentCard}. */
    public static final class Builder {
        private final String name;
        private final String description;
        private final String version;
        private AgentProvider provider;
        private URI documentationUrl;
        private AgentCapabilities capabilities = AgentCapabilities.none();
        private List<String> defaultInputModes = List.of("text/plain");
        private List<String> defaultOutputModes = List.of("text/plain");
        private List<AgentSkill> skills = List.of();
        private Map<String, SecurityScheme> securitySchemes = Map.of();
        private List<SecurityRequirement> securityRequirements = List.of();
        private URI iconUrl;
        private List<AgentInterface> supportedInterfaces = List.of();
        private List<AgentCardSignature> signatures = List.of();
        private Map<String, StateValue> additionalProperties = Map.of();

        private Builder(String name, String description, String version) {
            this.name = name;
            this.description = description;
            this.version = version;
        }

        /** Sets provider information. */
        public Builder provider(AgentProvider value) {
            provider = value;
            return this;
        }

        /** Sets the documentation URL. */
        public Builder documentationUrl(URI value) {
            documentationUrl = value;
            return this;
        }

        /** Sets capabilities. */
        public Builder capabilities(AgentCapabilities value) {
            capabilities = value;
            return this;
        }

        /** Sets default input modes. */
        public Builder defaultInputModes(List<String> values) {
            defaultInputModes = values;
            return this;
        }

        /** Sets default output modes. */
        public Builder defaultOutputModes(List<String> values) {
            defaultOutputModes = values;
            return this;
        }

        /** Sets skills. */
        public Builder skills(List<AgentSkill> values) {
            skills = values;
            return this;
        }

        /** Sets security schemes. */
        public Builder securitySchemes(Map<String, SecurityScheme> values) {
            securitySchemes = values;
            return this;
        }

        /** Sets alternative security requirements. */
        public Builder securityRequirements(List<SecurityRequirement> values) {
            securityRequirements = values;
            return this;
        }

        /** Sets the icon URL. */
        public Builder iconUrl(URI value) {
            iconUrl = value;
            return this;
        }

        /** Sets ordered transport interfaces. */
        public Builder supportedInterfaces(List<AgentInterface> values) {
            supportedInterfaces = values;
            return this;
        }

        /** Sets card signatures. */
        public Builder signatures(List<AgentCardSignature> values) {
            signatures = values;
            return this;
        }

        /** Sets safely retained additive properties. */
        public Builder additionalProperties(Map<String, StateValue> values) {
            additionalProperties = values;
            return this;
        }

        /** Creates the immutable card. */
        public AgentCard build() {
            return new AgentCard(
                    name,
                    description,
                    provider,
                    version,
                    documentationUrl,
                    capabilities,
                    defaultInputModes,
                    defaultOutputModes,
                    skills,
                    securitySchemes,
                    securityRequirements,
                    iconUrl,
                    supportedInterfaces,
                    signatures,
                    additionalProperties);
        }
    }
}
