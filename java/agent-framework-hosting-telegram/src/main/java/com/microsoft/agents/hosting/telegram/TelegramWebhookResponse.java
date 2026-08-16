// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

/**
 * Represents the HTTP-neutral result of one Telegram webhook request.
 *
 * @param statusCode HTTP status an application adapter should return
 * @param disposition handling disposition
 * @param updateId parsed Telegram update identifier when available
 * @param outboundMessageId sent Telegram message identifier when processed
 * @param errorCode sanitized failure code when rejected or cancelled
 */
public record TelegramWebhookResponse(
        int statusCode,
        TelegramWebhookDisposition disposition,
        Long updateId,
        Long outboundMessageId,
        TelegramWebhookErrorCode errorCode) {
    /** Creates a structurally valid response. */
    public TelegramWebhookResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status.");
        }
        Objects.requireNonNull(disposition, "disposition");
        boolean processed = disposition == TelegramWebhookDisposition.PROCESSED;
        boolean unsupported = disposition == TelegramWebhookDisposition.UNSUPPORTED;
        boolean failed = disposition == TelegramWebhookDisposition.REJECTED
                || disposition == TelegramWebhookDisposition.CANCELLED;
        if (processed != (outboundMessageId != null)
                || (processed && errorCode != null)
                || (unsupported && (outboundMessageId != null || errorCode != null))
                || failed != (errorCode != null)) {
            throw new IllegalArgumentException("Webhook response payload does not match its disposition.");
        }
    }
}
