// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

/** Classifies sanitized Telegram Bot API client failures. */
public enum TelegramBotErrorCode {
    /** The client was closed before the request completed. */
    CLIENT_CLOSED,
    /** The configured concurrent request capacity was exhausted. */
    CONCURRENCY_LIMIT,
    /** The encoded request exceeded the configured byte bound. */
    REQUEST_TOO_LARGE,
    /** The request was cancelled. */
    CANCELLED,
    /** The request exceeded its configured deadline. */
    TIMEOUT,
    /** The HTTP exchange failed before a valid response was received. */
    TRANSPORT_ERROR,
    /** The HTTP endpoint returned a non-success status. */
    HTTP_ERROR,
    /** Telegram returned an {@code ok=false} Bot API envelope. */
    API_ERROR,
    /** The response exceeded the configured byte bound. */
    RESPONSE_TOO_LARGE,
    /** The response was not a valid supported Bot API envelope. */
    INVALID_RESPONSE
}
