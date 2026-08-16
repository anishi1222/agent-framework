// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

/** Represents a sanitized Telegram Bot API client failure. */
public final class TelegramBotException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final TelegramBotErrorCode code;

    private final Integer httpStatus;

    private final Integer apiErrorCode;

    /**
     * Creates a sanitized Bot API failure.
     *
     * @param code stable client error code
     * @param httpStatus optional HTTP status
     * @param apiErrorCode optional Telegram Bot API error code
     */
    public TelegramBotException(TelegramBotErrorCode code, Integer httpStatus, Integer apiErrorCode) {
        this(code, httpStatus, apiErrorCode, null);
    }

    TelegramBotException(TelegramBotErrorCode code, Integer httpStatus, Integer apiErrorCode, Throwable cause) {
        super(message(Objects.requireNonNull(code, "code")), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.apiErrorCode = apiErrorCode;
    }

    /**
     * Returns the stable client error code.
     *
     * @return error code
     */
    public TelegramBotErrorCode code() {
        return code;
    }

    /**
     * Returns the HTTP status when one was received.
     *
     * @return HTTP status, or {@code null}
     */
    public Integer httpStatus() {
        return httpStatus;
    }

    /**
     * Returns Telegram's Bot API error code when present.
     *
     * @return Bot API error code, or {@code null}
     */
    public Integer apiErrorCode() {
        return apiErrorCode;
    }

    private static String message(TelegramBotErrorCode code) {
        return switch (code) {
            case CLIENT_CLOSED -> "Telegram Bot API client is closed.";
            case CONCURRENCY_LIMIT -> "Telegram Bot API request capacity is exhausted.";
            case REQUEST_TOO_LARGE -> "Telegram Bot API request exceeded the configured limit.";
            case CANCELLED -> "Telegram Bot API request was cancelled.";
            case TIMEOUT -> "Telegram Bot API request exceeded its deadline.";
            case TRANSPORT_ERROR -> "Telegram Bot API transport failed.";
            case HTTP_ERROR -> "Telegram Bot API endpoint returned an unexpected HTTP status.";
            case API_ERROR -> "Telegram Bot API rejected the request.";
            case RESPONSE_TOO_LARGE -> "Telegram Bot API response exceeded the configured limit.";
            case INVALID_RESPONSE -> "Telegram Bot API returned an invalid response.";
        };
    }
}
