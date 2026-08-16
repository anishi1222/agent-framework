// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.AgentExecutionException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Represents a sanitized failure from the OpenAI provider.
 *
 * <p>Provider response bodies and credentials are intentionally not retained.
 */
public class OpenAIProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private static final int MAX_IDENTIFIER_LENGTH = 128;

    private static final int MAX_MESSAGE_LENGTH = 512;

    private final Integer statusCode;

    private final String requestId;

    private final String errorCode;

    /**
     * Creates a sanitized provider failure.
     *
     * @param message safe diagnostic message
     * @param statusCode optional HTTP status
     * @param requestId optional provider request identifier
     * @param errorCode optional provider error code
     */
    public OpenAIProviderException(String message, Integer statusCode, String requestId, String errorCode) {
        super(safeMessage(message));
        this.statusCode = statusCode;
        this.requestId = safeIdentifier(requestId);
        this.errorCode = safeIdentifier(errorCode);
    }

    /**
     * Returns the optional HTTP status.
     *
     * @return status code
     */
    public final OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    /**
     * Returns the optional provider request identifier.
     *
     * @return request identifier
     */
    public final Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Returns the optional provider error code.
     *
     * @return error code
     */
    public final Optional<String> errorCode() {
        return Optional.ofNullable(errorCode);
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
}
