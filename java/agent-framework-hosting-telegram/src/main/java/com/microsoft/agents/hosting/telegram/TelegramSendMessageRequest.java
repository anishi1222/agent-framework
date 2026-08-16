// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

/**
 * Represents the supported Telegram {@code sendMessage} request surface.
 *
 * @param chatId target Telegram chat identifier
 * @param text non-empty message text bounded to Telegram's 4096 UTF-16 code-unit limit
 */
public record TelegramSendMessageRequest(long chatId, String text) {
    /** Creates a validated request. */
    public TelegramSendMessageRequest {
        if (chatId == 0) {
            throw new IllegalArgumentException("chatId must not be zero.");
        }
        text = TelegramValidation.wellFormedUtf16(TelegramValidation.nonBlank(text, "text"), "text");
        if (text.length() > TelegramWebhookOptions.TELEGRAM_MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds Telegram's maximum message length.");
        }
    }
}
