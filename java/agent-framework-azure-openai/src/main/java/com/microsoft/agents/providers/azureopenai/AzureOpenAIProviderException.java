// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.core.AgentExecutionException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Represents a sanitized Azure OpenAI provider failure.
 *
 * <p>Response bodies, prompts, tool payloads, tokens, and API keys are intentionally not retained.
 */
public final class AzureOpenAIProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final int MAX_MESSAGE_LENGTH = 512;

    private final Kind kind;

    private final Integer statusCode;

    private final String requestId;

    private final String correlationId;

    private final String serviceCode;

    /**
     * Creates a sanitized provider failure.
     *
     * @param message safe diagnostic message
     * @param kind failure category
     * @param statusCode optional HTTP status
     * @param requestId optional request identifier
     * @param correlationId optional correlation identifier
     * @param serviceCode optional Azure service error code
     */
    public AzureOpenAIProviderException(
            String message, Kind kind, Integer statusCode, String requestId, String correlationId, String serviceCode) {
        super(safeMessage(message));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
        this.requestId = safeIdentifier(requestId);
        this.correlationId = safeIdentifier(correlationId);
        this.serviceCode = safeIdentifier(serviceCode);
    }

    /**
     * Returns the failure category.
     *
     * @return failure category
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the optional HTTP status.
     *
     * @return status code
     */
    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    /**
     * Returns the optional request identifier.
     *
     * @return request identifier
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Returns the optional correlation identifier.
     *
     * @return correlation identifier
     */
    public Optional<String> correlationId() {
        return Optional.ofNullable(correlationId);
    }

    /**
     * Returns the optional service error code.
     *
     * @return service error code
     */
    public Optional<String> serviceCode() {
        return Optional.ofNullable(serviceCode);
    }

    static String safeIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '-'
                    || character == '_'
                    || character == '.'
                    || character == ':')) {
                return null;
            }
        }
        return value;
    }

    private static String safeMessage(String message) {
        Objects.requireNonNull(message, "message");
        StringBuilder safe = new StringBuilder(Math.min(message.length(), MAX_MESSAGE_LENGTH));
        for (int index = 0; index < message.length() && safe.length() < MAX_MESSAGE_LENGTH; index++) {
            char character = message.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        return safe.toString();
    }

    /** Categorizes failures without exposing a response body. */
    public enum Kind {
        /** Authentication or authorization failed. */
        AUTHENTICATION,
        /** The service throttled the request. */
        RATE_LIMIT,
        /** The configured timeout elapsed. */
        TIMEOUT,
        /** The service returned an unsupported or invalid protocol value. */
        PROTOCOL,
        /** The request contains content unsupported by this adapter. */
        UNSUPPORTED_CONTENT,
        /** The request selects an option unsupported by the configured Azure API version. */
        UNSUPPORTED_OPTION,
        /** The streaming retention bound was exceeded. */
        STREAM_OVERFLOW,
        /** Another SDK or transport failure occurred. */
        TRANSPORT
    }
}
