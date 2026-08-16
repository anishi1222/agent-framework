// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Configures the stable official SDK OpenTelemetry exporter settings.
 *
 * @param otlpEndpoint optional absolute HTTP(S) OTLP endpoint
 * @param otlpProtocol optional {@code http/json} or {@code http/protobuf} protocol
 * @param filePath optional telemetry output file
 * @param exporterType optional {@code otlp-http} or {@code file} exporter
 * @param sourceName optional source name
 * @param captureContent optional message-content capture decision
 */
public record GitHubCopilotTelemetryConfig(
        URI otlpEndpoint,
        String otlpProtocol,
        Path filePath,
        String exporterType,
        String sourceName,
        Boolean captureContent) {
    /** Creates validated telemetry configuration. */
    public GitHubCopilotTelemetryConfig {
        if (otlpEndpoint != null) {
            otlpEndpoint = otlpEndpoint.normalize();
            String scheme = otlpEndpoint.getScheme();
            if (!otlpEndpoint.isAbsolute()
                    || otlpEndpoint.getHost() == null
                    || otlpEndpoint.getRawUserInfo() != null
                    || otlpEndpoint.getRawFragment() != null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(
                        "otlpEndpoint must be an absolute HTTP(S) URI without user info or fragment.");
            }
        }
        otlpProtocol = optionalLower(otlpProtocol);
        if (otlpProtocol != null && !"http/json".equals(otlpProtocol) && !"http/protobuf".equals(otlpProtocol)) {
            throw new IllegalArgumentException("otlpProtocol must be http/json or http/protobuf.");
        }
        filePath = filePath == null ? null : filePath.toAbsolutePath().normalize();
        exporterType = optionalLower(exporterType);
        if (exporterType != null && !"otlp-http".equals(exporterType) && !"file".equals(exporterType)) {
            throw new IllegalArgumentException("exporterType must be otlp-http or file.");
        }
        if ("otlp-http".equals(exporterType) && otlpEndpoint == null) {
            throw new IllegalArgumentException("otlpEndpoint is required for the otlp-http exporter.");
        }
        if ("file".equals(exporterType) && filePath == null) {
            throw new IllegalArgumentException("filePath is required for the file exporter.");
        }
        sourceName = optional(sourceName);
    }

    private static String optionalLower(String value) {
        String result = optional(value);
        return result == null ? null : result.toLowerCase(Locale.ROOT);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
