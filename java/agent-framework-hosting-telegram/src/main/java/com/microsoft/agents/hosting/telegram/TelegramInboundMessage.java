// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

record TelegramInboundMessage(long updateId, long messageId, long chatId, String chatType, long userId, String text) {
    TelegramInboundMessage {
        if (updateId <= 0 || messageId <= 0 || chatId == 0 || userId <= 0) {
            throw new IllegalArgumentException("Telegram identifiers are invalid.");
        }
        chatType = TelegramValidation.wellFormedUtf16(TelegramValidation.nonBlank(chatType, "chatType"), "chatType");
        text = TelegramValidation.wellFormedUtf16(Objects.requireNonNull(text, "text"), "text");
    }
}
