// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Sends the bounded Telegram Bot API operations supported by the webhook adapter. */
@FunctionalInterface
public interface TelegramBotClient {
    /**
     * Sends one Telegram message.
     *
     * @param request sendMessage request
     * @param cancellation caller-owned cancellation signal
     * @return sent message result stage
     */
    CompletionStage<TelegramSendMessageResult> sendMessageAsync(
            TelegramSendMessageRequest request, RunCancellation cancellation);
}
