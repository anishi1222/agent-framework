// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

/** Classifies how one Telegram webhook request was handled. */
public enum TelegramWebhookDisposition {
    /** The update ran through Agent Framework and produced an outbound Telegram message. */
    PROCESSED,
    /** The valid update used an explicitly unsupported Telegram update or message shape. */
    UNSUPPORTED,
    /** The request was rejected or processing failed. */
    REJECTED,
    /** Caller cancellation stopped processing. */
    CANCELLED
}
