// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Defines framework-owned A2A security-scheme variants without acquiring credentials.
 */
public sealed interface SecurityScheme
        permits SecurityScheme.ApiKey,
                SecurityScheme.Http,
                SecurityScheme.MutualTls,
                SecurityScheme.OAuth2,
                SecurityScheme.OpenIdConnect {
    /**
     * Returns the optional human-readable description.
     *
     * @return description, or {@code null}
     */
    String description();

    /**
     * Defines an API-key scheme.
     *
     * @param name key name
     * @param location {@code header}, {@code query}, or {@code cookie}
     * @param description optional description
     */
    record ApiKey(String name, String location, String description) implements SecurityScheme {
        /** Creates a validated API-key scheme. */
        public ApiKey {
            name = A2AValidation.nonBlank(name, "name");
            location = A2AValidation.nonBlank(location, "location");
            if (!List.of("header", "query", "cookie").contains(location)) {
                throw new com.microsoft.agents.core.ValidationException("location must be header, query, or cookie.");
            }
            description = A2AValidation.optionalNonBlank(description, "description");
        }
    }

    /**
     * Defines an HTTP authentication scheme.
     *
     * @param scheme HTTP authentication scheme
     * @param bearerFormat optional bearer format
     * @param description optional description
     */
    record Http(String scheme, String bearerFormat, String description) implements SecurityScheme {
        /** Creates a validated HTTP scheme. */
        public Http {
            scheme = A2AValidation.nonBlank(scheme, "scheme");
            bearerFormat = A2AValidation.optionalNonBlank(bearerFormat, "bearerFormat");
            description = A2AValidation.optionalNonBlank(description, "description");
        }
    }

    /**
     * Defines one OAuth flow.
     *
     * @param authorizationUrl optional authorization endpoint
     * @param tokenUrl optional token endpoint
     * @param refreshUrl optional refresh endpoint
     * @param scopes declared scope descriptions
     */
    record OAuthFlow(URI authorizationUrl, URI tokenUrl, URI refreshUrl, Map<String, String> scopes) {
        /** Creates a validated OAuth flow. */
        public OAuthFlow {
            if (authorizationUrl != null) {
                authorizationUrl = A2AValidation.absoluteUri(authorizationUrl, "authorizationUrl");
            }
            if (tokenUrl != null) {
                tokenUrl = A2AValidation.absoluteUri(tokenUrl, "tokenUrl");
            }
            if (refreshUrl != null) {
                refreshUrl = A2AValidation.absoluteUri(refreshUrl, "refreshUrl");
            }
            scopes = A2AValidation.map(scopes, "scopes");
        }
    }

    /**
     * Defines OAuth 2.0 flows.
     *
     * @param flows flow names mapped to configurations
     * @param metadataUrl optional RFC 8414 metadata URL
     * @param description optional description
     */
    record OAuth2(Map<String, OAuthFlow> flows, URI metadataUrl, String description) implements SecurityScheme {
        /** Creates a validated OAuth 2.0 scheme. */
        public OAuth2 {
            flows = A2AValidation.map(flows, "flows");
            if (flows.isEmpty()) {
                throw new com.microsoft.agents.core.ValidationException("flows must not be empty.");
            }
            if (metadataUrl != null) {
                metadataUrl = A2AValidation.absoluteUri(metadataUrl, "metadataUrl");
            }
            description = A2AValidation.optionalNonBlank(description, "description");
        }
    }

    /**
     * Defines OpenID Connect discovery.
     *
     * @param openIdConnectUrl discovery URL
     * @param description optional description
     */
    record OpenIdConnect(URI openIdConnectUrl, String description) implements SecurityScheme {
        /** Creates a validated OpenID Connect scheme. */
        public OpenIdConnect {
            openIdConnectUrl = A2AValidation.absoluteUri(openIdConnectUrl, "openIdConnectUrl");
            description = A2AValidation.optionalNonBlank(description, "description");
        }
    }

    /**
     * Defines mutual TLS authentication.
     *
     * @param description optional description
     */
    record MutualTls(String description) implements SecurityScheme {
        /** Creates a validated mutual TLS scheme. */
        public MutualTls {
            description = A2AValidation.optionalNonBlank(description, "description");
        }
    }
}
