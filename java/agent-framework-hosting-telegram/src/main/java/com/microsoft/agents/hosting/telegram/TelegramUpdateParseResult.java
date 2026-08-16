// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

record TelegramUpdateParseResult(long updateId, TelegramInboundMessage message, String unsupportedType) {
    TelegramUpdateParseResult {
        if (updateId <= 0) {
            throw new IllegalArgumentException("updateId must be positive.");
        }
        if ((message == null) == (unsupportedType == null)) {
            throw new IllegalArgumentException("Exactly one of message or unsupportedType must be present.");
        }
        if (unsupportedType != null) {
            unsupportedType = TelegramValidation.nonBlank(unsupportedType, "unsupportedType");
        }
    }

    static TelegramUpdateParseResult supported(TelegramInboundMessage message) {
        return new TelegramUpdateParseResult(
                Objects.requireNonNull(message, "message").updateId(), message, null);
    }

    static TelegramUpdateParseResult unsupported(long updateId, String type) {
        return new TelegramUpdateParseResult(updateId, null, type);
    }

    boolean supported() {
        return message != null;
    }
}
