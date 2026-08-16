// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Contains one exact HTTPS Cosmos DB account endpoint.
 *
 * @param value account URI
 */
public record CosmosEndpoint(URI value) {
    private static final Set<String> ACCOUNT_SUFFIXES =
            Set.of(".documents.azure.com", ".documents.azure.us", ".documents.azure.cn", ".documents.cloudapi.de");

    /** Creates and validates an exact account endpoint. */
    public CosmosEndpoint {
        CosmosValidation.requireNonNull(value, "value");
        String host = value.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean accountHost = ACCOUNT_SUFFIXES.stream().anyMatch(normalizedHost::endsWith);
        String accountLabel = ACCOUNT_SUFFIXES.stream()
                .filter(normalizedHost::endsWith)
                .map(suffix -> normalizedHost.substring(0, normalizedHost.length() - suffix.length()))
                .findFirst()
                .orElse("");
        String path = value.getPath();
        if (!"https".equalsIgnoreCase(value.getScheme())
                || !accountHost
                || !accountLabel.matches("[a-z0-9](?:[a-z0-9-]{1,42}[a-z0-9])?")
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || (value.getPort() != -1 && value.getPort() != 443)
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new ValidationException(
                    "Cosmos endpoint must be an exact HTTPS account host with no path, query, fragment, or user info.");
        }
        value = URI.create("https://" + normalizedHost + "/");
    }

    /**
     * Parses an account endpoint.
     *
     * @param value endpoint text
     * @return validated endpoint
     */
    public static CosmosEndpoint parse(String value) {
        return new CosmosEndpoint(URI.create(CosmosValidation.requireNonBlank(value, "value")));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
