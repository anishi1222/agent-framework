// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

/**
 * Represents the supported result returned by Telegram {@code sendMessage}.
 *
 * @param messageId Telegram message identifier
 */
public record TelegramSendMessageResult(long messageId) {
    /** Creates a validated result. */
    public TelegramSendMessageResult {
        if (messageId <= 0) {
            throw new IllegalArgumentException("messageId must be greater than zero.");
        }
    }
}
