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

    /** Defines exactly one supported OAuth 2.0 flow variant. */
    sealed interface OAuthFlow permits AuthorizationCode, ClientCredentials, DeviceCode {
        /** Returns the optional refresh endpoint. */
        URI refreshUrl();

        /** Returns immutable scope descriptions. */
        Map<String, String> scopes();
    }

    /**
     * Defines the authorization-code flow.
     *
     * @param authorizationUrl authorization endpoint
     * @param tokenUrl token endpoint
     * @param refreshUrl optional refresh endpoint
     * @param scopes scope descriptions
     * @param pkceRequired whether PKCE is mandatory
     */
    record AuthorizationCode(
            URI authorizationUrl, URI tokenUrl, URI refreshUrl, Map<String, String> scopes, boolean pkceRequired)
            implements OAuthFlow {
        /** Creates a validated authorization-code flow. */
        public AuthorizationCode {
            authorizationUrl = A2AValidation.absoluteUri(authorizationUrl, "authorizationUrl");
            tokenUrl = A2AValidation.absoluteUri(tokenUrl, "tokenUrl");
            if (refreshUrl != null) {
                refreshUrl = A2AValidation.absoluteUri(refreshUrl, "refreshUrl");
            }
            scopes = A2AValidation.map(scopes, "scopes");
        }
    }

    /**
     * Defines the client-credentials flow.
     *
     * @param tokenUrl token endpoint
     * @param refreshUrl optional refresh endpoint
     * @param scopes scope descriptions
     */
    record ClientCredentials(URI tokenUrl, URI refreshUrl, Map<String, String> scopes) implements OAuthFlow {
        /** Creates a validated client-credentials flow. */
        public ClientCredentials {
            tokenUrl = A2AValidation.absoluteUri(tokenUrl, "tokenUrl");
            if (refreshUrl != null) {
                refreshUrl = A2AValidation.absoluteUri(refreshUrl, "refreshUrl");
            }
            scopes = A2AValidation.map(scopes, "scopes");
        }
    }

    /**
     * Defines the device-code flow.
     *
     * @param deviceAuthorizationUrl device authorization endpoint
     * @param tokenUrl token endpoint
     * @param refreshUrl optional refresh endpoint
     * @param scopes scope descriptions
     */
    record DeviceCode(URI deviceAuthorizationUrl, URI tokenUrl, URI refreshUrl, Map<String, String> scopes)
            implements OAuthFlow {
        /** Creates a validated device-code flow. */
        public DeviceCode {
            deviceAuthorizationUrl = A2AValidation.absoluteUri(deviceAuthorizationUrl, "deviceAuthorizationUrl");
            tokenUrl = A2AValidation.absoluteUri(tokenUrl, "tokenUrl");
            if (refreshUrl != null) {
                refreshUrl = A2AValidation.absoluteUri(refreshUrl, "refreshUrl");
            }
            scopes = A2AValidation.map(scopes, "scopes");
        }
    }

    /**
     * Defines OAuth 2.0 authentication.
     *
     * @param flow exactly one flow variant
     * @param metadataUrl optional RFC 8414 metadata URL
     * @param description optional description
     */
    record OAuth2(OAuthFlow flow, URI metadataUrl, String description) implements SecurityScheme {
        /** Creates a validated OAuth 2.0 scheme. */
        public OAuth2 {
            flow = java.util.Objects.requireNonNull(flow, "flow");
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
