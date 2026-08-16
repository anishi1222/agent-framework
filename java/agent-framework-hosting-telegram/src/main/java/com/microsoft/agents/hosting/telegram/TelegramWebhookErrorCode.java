// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

/** Identifies stable sanitized Telegram webhook failures. */
public enum TelegramWebhookErrorCode {
    /** The adapter is closed. */
    CLOSED,
    /** The HTTP method is unsupported. */
    METHOD_NOT_ALLOWED,
    /** The request media type is unsupported. */
    UNSUPPORTED_MEDIA_TYPE,
    /** The webhook secret token is missing, ambiguous, or invalid. */
    UNAUTHENTICATED,
    /** The request body exceeds the configured byte bound. */
    PAYLOAD_TOO_LARGE,
    /** The update is not valid bounded JSON. */
    MALFORMED_UPDATE,
    /** A supported update shape has invalid required fields. */
    INVALID_UPDATE,
    /** Generic hosting rejected or failed the run. */
    DISPATCH_FAILED,
    /** The outbound Telegram Bot API call failed. */
    OUTBOUND_FAILED,
    /** The caller cancelled processing. */
    CANCELLED,
    /** End-to-end processing exceeded the configured deadline. */
    TIMEOUT
}
